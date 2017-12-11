/*
 * Copyright (C) 2017 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.common.base.Preconditions.checkArgument;
import static dagger.internal.codegen.Accessibility.isTypeAccessibleFrom;
import static dagger.internal.codegen.MemberSelect.staticMemberSelect;

import com.google.common.base.Supplier;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import java.util.Optional;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/** A binding expression that uses an instance of a {@link FrameworkType}. */
final class FrameworkInstanceBindingExpression extends BindingExpression {
  private final ComponentBindingExpressions componentBindingExpressions;
  private final Supplier<MemberSelect> frameworkFieldSupplier;
  private final FrameworkType frameworkType;
  private final DaggerTypes types;
  private final Elements elements;

  /** Returns a binding expression for a binding. */
  static FrameworkInstanceBindingExpression create(
      ResolvedBindings resolvedBindings,
      BindingGraph graph,
      SubcomponentNames subcomponentNames,
      GeneratedComponentModel generatedComponentModel,
      ComponentBindingExpressions componentBindingExpressions,
      ComponentRequirementFields componentRequirementFields,
      ReferenceReleasingManagerFields referenceReleasingManagerFields,
      boolean isProducerFromProvider,
      OptionalFactories optionalFactories,
      CompilerOptions compilerOptions,
      DaggerTypes types,
      Elements elements) {
    FrameworkType frameworkType = resolvedBindings.bindingType().frameworkType();
    checkArgument(!isProducerFromProvider || frameworkType.equals(FrameworkType.PROVIDER));

    Optional<MemberSelect> staticMemberSelect = staticMemberSelect(resolvedBindings);
    Supplier<MemberSelect> frameworkFieldSupplier;
    if (!isProducerFromProvider && staticMemberSelect.isPresent()) {
      frameworkFieldSupplier = staticMemberSelect::get;
    } else {
      FrameworkFieldInitializer fieldInitializer =
          new FrameworkFieldInitializer(
              resolvedBindings,
              subcomponentNames,
              generatedComponentModel,
              componentBindingExpressions,
              componentRequirementFields,
              referenceReleasingManagerFields,
              compilerOptions,
              graph,
              isProducerFromProvider,
              optionalFactories);
      frameworkFieldSupplier = fieldInitializer::getOrCreateMemberSelect;
    }

    return new FrameworkInstanceBindingExpression(
        resolvedBindings,
        componentBindingExpressions,
        isProducerFromProvider ? FrameworkType.PRODUCER : frameworkType,
        frameworkFieldSupplier,
        types,
        elements);
  }

  private FrameworkInstanceBindingExpression(
      ResolvedBindings resolvedBindings,
      ComponentBindingExpressions componentBindingExpressions,
      FrameworkType frameworkType,
      Supplier<MemberSelect> frameworkFieldSupplier,
      DaggerTypes types,
      Elements elements) {
    super(resolvedBindings);
    this.componentBindingExpressions = componentBindingExpressions;
    this.frameworkType = frameworkType;
    this.frameworkFieldSupplier = frameworkFieldSupplier;
    this.types = types;
    this.elements = elements;
  }

  /**
   * The expression for the framework instance for this binding. If the instance comes from a
   * component field, it will be {@link GeneratedComponentModel#addInitialization(CodeBlock)
   * initialized} and {@link GeneratedComponentModel#addField(GeneratedComponentModel.FieldSpecKind,
   * FieldSpec) added} to the component the first time this method is invoked.
   */
  @Override
  Expression getDependencyExpression(
      DependencyRequest.Kind requestKind, ClassName requestingClass) {
    if (requestKind.equals(frameworkRequestKind())) {
      MemberSelect memberSelect = frameworkFieldSupplier.get();
      TypeMirror expressionType =
          isTypeAccessibleFrom(instanceType(), requestingClass.packageName())
                  || isInlinedFactoryCreation(memberSelect)
              ? types.wrapType(instanceType(), resolvedBindings().frameworkClass())
              : rawFrameworkType();
      return Expression.create(expressionType, memberSelect.getExpressionFor(requestingClass));
    }

    // The following expressions form a composite with the expression for the framework type.
    // For example, the expression for a DependencyRequest.Kind.LAZY is a composite of the
    // expression for a DependencyRequest.Kind.PROVIDER (the framework type):
    //    lazyExpression = DoubleCheck.lazy(providerExpression);
    return frameworkType.to(
        requestKind,
        componentBindingExpressions.getDependencyExpression(
            resolvedBindings().bindingKey(), frameworkRequestKind(), requestingClass),
        types);
  }

  /** Returns the request kind that matches the framework type. */
  private DependencyRequest.Kind frameworkRequestKind() {
    switch (frameworkType) {
      case PROVIDER:
        return DependencyRequest.Kind.PROVIDER;
      case PRODUCER:
        return DependencyRequest.Kind.PRODUCER;
      case MEMBERS_INJECTOR:
        return DependencyRequest.Kind.MEMBERS_INJECTOR;
      default:
        throw new AssertionError();
    }
  }

  /**
   * The instance type {@code T} of this {@code FrameworkType<T>}. For {@link
   * MembersInjectionBinding}s, this is the {@linkplain Key#type() key type}; for {@link
   * ContributionBinding}s, this the {@link ContributionBinding#contributedType()}.
   */
  private TypeMirror instanceType() {
    return resolvedBindings()
        .membersInjectionBinding()
        .map(binding -> binding.key().type())
        .orElseGet(() -> resolvedBindings().contributionBinding().contributedType());
  }

  /**
   * Returns {@code true} if a factory is created inline each time it is requested. For example, in
   * the initialization {@code this.fooProvider = Foo_Factory.create(Bar_Factory.create());}, {@code
   * Bar_Factory} is considered to be inline.
   *
   * <p>This is used in {@link #getDependencyExpression(DependencyRequest.Kind, ClassName)} when
   * determining the type of a factory. Normally if the {@link #instanceType()} is not accessible
   * from the component, the type of the expression will be a raw {@link javax.inject.Provider}.
   * However, if the factory is created inline, even if contributed type is not accessible, javac
   * will still be able to determine the type that is returned from the {@code Foo_Factory.create()}
   * method.
   */
  private static boolean isInlinedFactoryCreation(MemberSelect memberSelect) {
    return memberSelect.staticMember();
  }

  private DeclaredType rawFrameworkType() {
    return types.getDeclaredType(
        elements.getTypeElement(resolvedBindings().frameworkClass().getCanonicalName()));
  }
}

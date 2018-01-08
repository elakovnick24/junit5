/*
 * Copyright 2015-2018 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.engine.descriptor;

import static org.apiguardian.api.API.Status.INTERNAL;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.stream.Stream;

import org.apiguardian.api.API;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.engine.execution.ExecutableInvoker;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.CollectionUtils;
import org.junit.platform.commons.util.PreconditionViolationException;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;

/**
 * {@link org.junit.platform.engine.TestDescriptor TestDescriptor} for
 * {@link org.junit.jupiter.api.TestFactory @TestFactory} methods.
 *
 * @since 5.0
 */
@API(status = INTERNAL, since = "5.0")
public class TestFactoryTestDescriptor extends TestMethodTestDescriptor {

	public static final String DYNAMIC_CONTAINER_SEGMENT_TYPE = "dynamic-container";
	public static final String DYNAMIC_TEST_SEGMENT_TYPE = "dynamic-test";

	private static final ExecutableInvoker executableInvoker = new ExecutableInvoker();

	public TestFactoryTestDescriptor(UniqueId uniqueId, Class<?> testClass, Method testMethod) {
		super(uniqueId, testClass, testMethod);
	}

	// --- TestDescriptor ------------------------------------------------------

	@Override
	public Type getType() {
		return Type.CONTAINER;
	}

	@Override
	public boolean mayRegisterTests() {
		return true;
	}

	// --- Node ----------------------------------------------------------------

	@Override
	protected void invokeTestMethod(JupiterEngineExecutionContext context, DynamicTestExecutor dynamicTestExecutor) {
		ExtensionContext extensionContext = context.getExtensionContext();

		context.getThrowableCollector().execute(() -> {
			Object instance = extensionContext.getRequiredTestInstance();
			Object testFactoryMethodResult = executableInvoker.invoke(getTestMethod(), instance, extensionContext,
				context.getExtensionRegistry());
			TestSource source = getSource().orElseThrow(
				() -> new JUnitException("Illegal state: TestSource must be present"));
			try (Stream<DynamicNode> dynamicNodeStream = toDynamicNodeStream(testFactoryMethodResult)) {
				int index = 1;
				Iterator<DynamicNode> iterator = dynamicNodeStream.iterator();
				while (iterator.hasNext()) {
					DynamicNode dynamicNode = iterator.next();
					JupiterTestDescriptor descriptor = createDynamicDescriptor(this, dynamicNode, index++, source);
					dynamicTestExecutor.execute(descriptor);
				}
			}
			catch (ClassCastException ex) {
				throw invalidReturnTypeException(ex);
			}
		});
	}

	@SuppressWarnings("unchecked")
	private Stream<DynamicNode> toDynamicNodeStream(Object testFactoryMethodResult) {
		try {
			return (Stream<DynamicNode>) CollectionUtils.toStream(testFactoryMethodResult);
		}
		catch (PreconditionViolationException ex) {
			throw invalidReturnTypeException(ex);
		}
	}

	static JupiterTestDescriptor createDynamicDescriptor(JupiterTestDescriptor parent, DynamicNode node, int index,
			TestSource source) {
		JupiterTestDescriptor descriptor;
		if (node instanceof DynamicTest) {
			DynamicTest test = (DynamicTest) node;
			UniqueId uniqueId = parent.getUniqueId().append(DYNAMIC_TEST_SEGMENT_TYPE, "#" + index);
			descriptor = new DynamicTestTestDescriptor(uniqueId, index, test, source);
		}
		else {
			DynamicContainer container = (DynamicContainer) node;
			UniqueId uniqueId = parent.getUniqueId().append(DYNAMIC_CONTAINER_SEGMENT_TYPE, "#" + index);
			descriptor = new DynamicContainerTestDescriptor(uniqueId, index, container, source);
		}
		parent.addChild(descriptor);
		return descriptor;
	}

	private JUnitException invalidReturnTypeException(Throwable cause) {
		String message = String.format(
			"@TestFactory method [%s] must return a Stream, Collection, Iterable, or Iterator of %s.",
			getTestMethod().toGenericString(), DynamicNode.class.getName());
		return new JUnitException(message, cause);
	}

}

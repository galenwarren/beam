/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.runners.inprocess.evaluator;

import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.Bundle;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.InProcessEvaluationContext;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.InProcessExecutionContext;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.InProcessExecutionContext.InMemoryStepContext;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessTransformResult;
import com.google.cloud.dataflow.sdk.runners.inprocess.TransformEvaluator;
import com.google.cloud.dataflow.sdk.runners.inprocess.TransformEvaluatorFactory;
import com.google.cloud.dataflow.sdk.runners.inprocess.evaluator.ParDoInProcessEvaluator.BundleOutputManager;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo.BoundMulti;
import com.google.cloud.dataflow.sdk.util.DoFnRunner;
import com.google.cloud.dataflow.sdk.util.DoFnRunners;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionTuple;
import com.google.cloud.dataflow.sdk.values.TupleTag;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@link InProcessPipelineRunner} {@link TransformEvaluatorFactory} for the
 * {@link BoundMulti} primitive {@link PTransform}.
 */
public class ParDoMultiEvaluatorFactory implements TransformEvaluatorFactory {
  @Override
  public <T> TransformEvaluator<T> forApplication(
      AppliedPTransform<?, ?, ?> application,
      Bundle<?> inputBundle,
      InProcessEvaluationContext evaluationContext) {
    @SuppressWarnings({"unchecked", "rawtypes"})
    final ParDoInProcessEvaluator<T> multiEvaluator =
        createMultiEvaluator((AppliedPTransform) application, inputBundle, evaluationContext);
    return new TransformEvaluator<T>() {
      @Override
      public void processElement(WindowedValue<T> value) {
        multiEvaluator.processElement(value);
      }

      @Override
      public InProcessTransformResult finishBundle() {
        return multiEvaluator.finishBundle();
      }
    };
  }

  private static <InT, OuT> ParDoInProcessEvaluator<InT> createMultiEvaluator(
      AppliedPTransform<PCollection<InT>, PCollectionTuple, BoundMulti<InT, OuT>> application,
      Bundle<InT> inputBundle,
      InProcessEvaluationContext evaluationContext) {
    PCollectionTuple output = application.getOutput();
    Map<TupleTag<?>, PCollection<?>> outputs = output.getAll();
    Map<TupleTag<?>, Bundle<?>> outputBundles = new HashMap<>();
    for (Map.Entry<TupleTag<?>, PCollection<?>> outputEntry : outputs.entrySet()) {
      outputBundles.put(
          outputEntry.getKey(),
          evaluationContext.createBundle(inputBundle, outputEntry.getValue()));
    }
    InProcessExecutionContext executionContext = evaluationContext.getExecutionContext(application);
    String stepName = evaluationContext.getStepName(application);
    InMemoryStepContext stepContext =
        executionContext.getOrCreateStepContext(stepName, stepName, null);

    CounterSet counters = evaluationContext.createCounterSet();

    DoFn<InT, OuT> fn = application.getTransform().getFn();
    DoFnRunner<InT, OuT> runner =
        DoFnRunners.createDefault(
            evaluationContext.getPipelineOptions(),
            fn,
            evaluationContext.createSideInputReader(application.getTransform().getSideInputs()),
            BundleOutputManager.create(outputBundles),
            application.getTransform().getMainOutputTag(),
            application.getTransform().getSideOutputTags().getAll(),
            stepContext,
            counters.getAddCounterMutator(),
            application.getInput().getWindowingStrategy());

    runner.startBundle();

    return new ParDoInProcessEvaluator<>(runner, application, counters, outputBundles.values());
  }
}

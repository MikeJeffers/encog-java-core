/*
 * Encog(tm) Core v3.0 - Java Version
 * http://www.heatonresearch.com/encog/
 * http://code.google.com/p/encog-java/
 
 * Copyright 2008-2011 Heaton Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *   
 * For more information on Heaton Research copyrights, licenses 
 * and trademarks visit:
 * http://www.heatonresearch.com/copyright
 */
package org.encog.neural.networks.training.lma;

import org.encog.Encog;
import org.encog.EncogError;
import org.encog.engine.network.activation.ActivationFunction;
import org.encog.mathutil.IntRange;
import org.encog.ml.MLMethod;
import org.encog.ml.TrainingImplementationType;
import org.encog.ml.data.MLDataSet;
import org.encog.ml.train.BasicTraining;
import org.encog.neural.error.ErrorFunction;
import org.encog.neural.error.LinearErrorFunction;
import org.encog.neural.flat.FlatNetwork;
import org.encog.neural.flat.train.prop.GradientWorker;
import org.encog.neural.networks.BasicNetwork;
import org.encog.neural.networks.training.propagation.TrainingContinuation;
import org.encog.util.EngineArray;
import org.encog.util.concurrency.DetermineWorkload;
import org.encog.util.concurrency.EngineConcurrency;
import org.encog.util.concurrency.TaskGroup;

/**
 * Train a flat network using multithreading, and GPU support.
 * 
 * The training data must be indexable, it will be broken into groups for each
 * thread to process.
 * 
 * At the end of each iteration the training from each thread is aggregated back
 * to the neural network.
 * 
 */
public class LevenbergMarquardtTraining2 extends BasicTraining {

	/**
	 * The number of threads to use.
	 */
	private int numThreads;

	/**
	 * The partial derivatives.
	 */
	protected double[] partialDeriv;

	/**
	 * The last gradients, from the last training iteration.
	 */
	private final double[] lastGradient;

	/**
	 * The network to train.
	 */
	protected final BasicNetwork network;

	/**
	 * The training data.
	 */
	private final MLDataSet training;

	/**
	 * The network in indexable form.
	 */
	private final MLDataSet indexable;

	/**
	 * The workers.
	 */
	private LMAWorker[] workers;

	/**
	 * The total error. Used to take the average of.
	 */
	private double totalError;

	/**
	 * The current error is the average error over all of the threads.
	 */
	protected double currentError;
	
	/**
	 * The last error.
	 */
	protected double lastError;

	/**
	 * Reported exception from the threads.
	 */
	private Throwable reportedException;

	/**
	 * The iteration.
	 */
	private int iteration;

	/**
	 * The flat spot constants.
	 */
	private double[] flatSpot;
	
	/**
	 * Should we fix flat spots.
	 */
	private boolean shouldFixFlatSpot;
	
	private FlatNetwork flat;
	
	private double[] weightDelta;
	
	/**
	 * The error function.
	 */
	private ErrorFunction ef = new LinearErrorFunction();

	/**
	 * Train a flat network multithreaded.
	 * 
	 * @param network
	 *            The network to train.
	 * @param training
	 *            The training data to use.
	 */
	public LevenbergMarquardtTraining2(final BasicNetwork network,
			final MLDataSet training) {
		super(TrainingImplementationType.Iterative);

		setTraining(training);
		this.training = training;
		this.network = network;
		this.flat = network.getFlat();

		this.partialDeriv = new double[this.flat.getWeights().length];
		this.lastGradient = new double[this.flat.getWeights().length];

		this.indexable = training;
		this.numThreads = 0;
		this.reportedException = null;
		this.shouldFixFlatSpot = true;
	}

	/**
	 * Calculate the gradients.
	 */
	public void calculateGradients() {
		if (this.workers == null) {
			init();
		}

		if (this.flat.getHasContext()) {
			this.workers[0].getNetwork().clearContext();
		}

		this.totalError = 0;

		if (this.workers.length > 1) {

			final TaskGroup group = EngineConcurrency.getInstance()
					.createTaskGroup();

			for (final LMAWorker worker : this.workers) {
				EngineConcurrency.getInstance().processTask(worker, group);
			}

			group.waitForComplete();
		} else {
			this.workers[0].run();
		}

		this.currentError = this.totalError / this.workers.length;

	}

	/**
	 * Copy the contexts to keep them consistent with multithreaded training.
	 */
	private void copyContexts() {

		// copy the contexts(layer outputO from each group to the next group
		for (int i = 0; i < (this.workers.length - 1); i++) {
			final double[] src = this.workers[i].getNetwork().getLayerOutput();
			final double[] dst = this.workers[i + 1].getNetwork()
					.getLayerOutput();
			EngineArray.arrayCopy(src, dst);
		}

		// copy the contexts from the final group to the real network
		EngineArray.arrayCopy(this.workers[this.workers.length - 1]
				.getNetwork().getLayerOutput(), this.flat.getLayerOutput());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void finishTraining() {
		// nothing to do
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final double getError() {
		return this.currentError;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int getIteration() {
		return this.iteration;
	}

	/**
	 * @return The gradients from the last iteration;
	 */
	public final double[] getLastGradient() {
		return this.lastGradient;
	}

	/**
	 * {@inheritDoc}
	 */
	public final int getNumThreads() {
		return this.numThreads;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final MLDataSet getTraining() {
		return this.training;
	}

	public void fixFlatSpot(final boolean e) {	
		this.shouldFixFlatSpot = e;
	}
	

	/**
	 * Init the process.
	 */
	private void init() {

		// fix flat spot, if needed
		this.flatSpot = new double[this.flat.getActivationFunctions().length];

		if (this.shouldFixFlatSpot) {
			for (int i = 0; i < this.flat.getActivationFunctions().length; i++) {
				final ActivationFunction af = this.flat.getActivationFunctions()[i];
				// if the diriv tends to 0 on either -1, 0.0 or 1, then 
				// add a flat-spot const.
				final double t1 = af.derivativeFunction(-1.0,-1.0);
				final double t2 = af.derivativeFunction(0.0,0.0);
				final double t3 = af.derivativeFunction(1.0,1.0);
				if ((Math.abs(t1) < Encog.DEFAULT_DOUBLE_EQUAL)
						|| (Math.abs(t2) < Encog.DEFAULT_DOUBLE_EQUAL)
						|| (Math.abs(t3) < Encog.DEFAULT_DOUBLE_EQUAL)) {
					this.flatSpot[i] = 0.1;
				} else {
					this.flatSpot[i] = 0.0;
				}
			}
		} else {
			EngineArray.fill(this.flatSpot, 0.0);
		}
		
		
		// setup workers
		final DetermineWorkload determine = new DetermineWorkload(
				this.numThreads, (int) this.indexable.getRecordCount());

		this.workers = new LMAWorker[determine.getThreadCount()];

		int index = 0;

		// handle CPU
		for (final IntRange r : determine.calculateWorkers()) {
			this.workers[index++] = new LMAWorker(this.flat.clone(),
					this, this.indexable.openAdditional(), r.getLow(),
					r.getHigh(), this.flatSpot, this.ef);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void iteration() {

		this.iteration++;

		calculateGradients();

		if (this.flat.isLimited()) {
			learnLimited();
		} else {
			learn();
		}
		
		this.lastError = this.currentError;

		for (final LMAWorker worker : this.workers) {
			EngineArray.arrayCopy(this.flat.getWeights(), 0,
					worker.getWeights(), 0, this.flat.getWeights().length);
		}

		if (this.flat.getHasContext()) {
			copyContexts();
		}

		if (this.reportedException != null) {
			throw (new EncogError(this.reportedException));
		}
	}

	/**
	 * Perform the specified number of training iterations. This is a basic
	 * implementation that just calls iteration the specified number of times.
	 * However, some training methods, particularly with the GPU, benefit
	 * greatly by calling with higher numbers than 1.
	 * 
	 * @param count
	 *            The number of training iterations.
	 */
	@Override
	public final void iteration(final int count) {
		for (int i = 0; i < count; i++) {
			iteration();
		}
	}

	/**
	 * Apply and learn.
	 */
	protected void learn() {
		final double[] weights = this.flat.getWeights();
		for (int i = 0; i < this.partialDeriv.length; i++) {
			weights[i] += this.weightDelta[i];
			this.partialDeriv[i] = 0;
		}
	}
	
	/**
	 * Apply and learn. This is the same as learn, but it checks to see if any
	 * of the weights are below the limit threshold. In this case, these weights
	 * are zeroed out. Having two methods allows the regular learn method, which
	 * is what is usually use, to be as fast as possible.
	 */
	protected void learnLimited() {
		final double limit = this.flat.getConnectionLimit();
		final double[] weights = this.flat.getWeights();
		for (int i = 0; i < this.partialDeriv.length; i++) {
			if (Math.abs(weights[i]) < limit) {
				weights[i] = 0;
			} else {
				weights[i] += this.weightDelta[i];
			}
			this.partialDeriv[i] = 0;
		}
	}

	/**
	 * Called by the worker threads to report the progress at each step.
	 * 
	 * @param gradients
	 *            The gradients from that worker.
	 * @param error
	 *            The error for that worker.
	 * @param ex
	 *            The exception.
	 */
	public final void report(final double[] gradients, final double error,
			final Throwable ex) {
		synchronized (this) {
			if (ex == null) {

				for (int i = 0; i < gradients.length; i++) {
					this.partialDeriv[i] += gradients[i];
				}
				this.totalError += error;
			} else {
				this.reportedException = ex;
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setIteration(final int iteration) {
		this.iteration = iteration;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setNumThreads(final int numThreads) {
		this.numThreads = numThreads;
	}
	
	/**
	 * Set the error function.
	 * @param ef The error function.
	 */
	public void setErrorFunction(ErrorFunction ef) {
		this.ef = ef;
	}
	
	/**
	 * @return The error function.
	 */
	public ErrorFunction getErrorFunction() {
		return this.ef;
	}

	@Override
	public boolean canContinue() {
		return false;
	}

	@Override
	public TrainingContinuation pause() {
		return null;
	}

	@Override
	public void resume(TrainingContinuation state) {		
	}

	@Override
	public MLMethod getMethod() {
		return this.network;
	}
	
	protected void performLMA() {
		
	}
}
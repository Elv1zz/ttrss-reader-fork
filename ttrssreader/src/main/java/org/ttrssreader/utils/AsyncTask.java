/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ttrssreader.utils;

import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;

import java.util.ArrayDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;

/**
 * see @android.os.AsyncTask
 */
public abstract class AsyncTask<Params, Progress, Result> {
	private static final String LOG_TAG = "AsyncTask";

	private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
	// We want at least 2 threads and at most 4 threads in the core pool,
	// preferring to have 1 less than the CPU count to avoid saturating
	// the CPU with background work
	private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));
	private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
	private static final int KEEP_ALIVE_SECONDS = 30;

	private static final ThreadFactory sThreadFactory = new ThreadFactory() {
		private final AtomicInteger mCount = new AtomicInteger(1);

		public Thread newThread(Runnable r) {
			return new Thread(r, "AsyncTask #" + mCount.getAndIncrement());
		}
	};

	private static final BlockingQueue<Runnable> sPoolWorkQueue = new LinkedBlockingQueue<Runnable>(128);

	/**
	 * An {@link Executor} that can be used to execute tasks in parallel.
	 */
	public static final Executor THREAD_POOL_EXECUTOR;

	static {
		ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, sPoolWorkQueue, sThreadFactory);
		threadPoolExecutor.allowCoreThreadTimeOut(true);
		THREAD_POOL_EXECUTOR = threadPoolExecutor;
	}

	/**
	 * An {@link Executor} that executes tasks one at a time in serial
	 * order.  This serialization is global to a particular process.
	 */
	public static final Executor SERIAL_EXECUTOR = new SerialExecutor();

	private static final int MESSAGE_POST_RESULT = 0x1;
	private static final int MESSAGE_POST_PROGRESS = 0x2;

	private static volatile Executor sDefaultExecutor = SERIAL_EXECUTOR;
	private static InternalHandler sHandler;

	private final WorkerRunnable<Params, Result> mWorker;
	private final FutureTask<Result> mFuture;

	private volatile Status mStatus = Status.PENDING;

	private final AtomicBoolean mCancelled = new AtomicBoolean();
	private final AtomicBoolean mTaskInvoked = new AtomicBoolean();

	private final Handler mHandler;

	private static class SerialExecutor implements Executor {
		final ArrayDeque<Runnable> mTasks = new ArrayDeque<Runnable>();
		Runnable mActive;

		public synchronized void execute(final Runnable r) {
			mTasks.offer(new Runnable() {
				public void run() {
					try {
						r.run();
					} finally {
						scheduleNext();
					}
				}
			});
			if (mActive == null) {
				scheduleNext();
			}
		}

		protected synchronized void scheduleNext() {
			if ((mActive = mTasks.poll()) != null) {
				THREAD_POOL_EXECUTOR.execute(mActive);
			}
		}
	}

	/**
	 * Indicates the current status of the task. Each status will be set only once
	 * during the lifetime of a task.
	 */
	public enum Status {
		/**
		 * Indicates that the task has not been executed yet.
		 */
		PENDING, /**
		 * Indicates that the task is running.
		 */
		RUNNING, /**
		 * Indicates that {@link AsyncTask#onPostExecute} has finished.
		 */
		FINISHED,
	}

	private static Handler getMainHandler() {
		synchronized (AsyncTask.class) {
			if (sHandler == null) {
				sHandler = new InternalHandler(Looper.getMainLooper());
			}
			return sHandler;
		}
	}

	private Handler getHandler() {
		return mHandler;
	}

	/**
	 * @hide
	 */
	public static void setDefaultExecutor(Executor exec) {
		sDefaultExecutor = exec;
	}

	/**
	 * Creates a new asynchronous task. This constructor must be invoked on the UI thread.
	 */
	public AsyncTask() {
		this((Looper) null);
	}

	/**
	 * Creates a new asynchronous task. This constructor must be invoked on the UI thread.
	 *
	 * @hide
	 */
	public AsyncTask(Handler handler) {
		this(handler != null ? handler.getLooper() : null);
	}

	/**
	 * Creates a new asynchronous task. This constructor must be invoked on the UI thread.
	 *
	 * @hide
	 */
	public AsyncTask(Looper callbackLooper) {
		mHandler = callbackLooper == null || callbackLooper == Looper.getMainLooper() ? getMainHandler() : new Handler(callbackLooper);

		mWorker = new WorkerRunnable<Params, Result>() {
			public Result call() throws Exception {
				mTaskInvoked.set(true);
				Result result = null;
				try {
					Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
					//noinspection unchecked
					result = doInBackground(mParams);
					Binder.flushPendingCommands();
				} catch (Throwable tr) {
					mCancelled.set(true);
					throw tr;
				} finally {
					postResult(result);
				}
				return result;
			}
		};

		mFuture = new FutureTask<Result>(mWorker) {
			@Override
			protected void done() {
				try {
					postResultIfNotInvoked(get());
				} catch (InterruptedException e) {
					android.util.Log.w(LOG_TAG, e);
				} catch (ExecutionException e) {
					throw new RuntimeException("An error occurred while executing doInBackground()", e.getCause());
				} catch (CancellationException e) {
					postResultIfNotInvoked(null);
				}
			}
		};
	}

	private void postResultIfNotInvoked(Result result) {
		final boolean wasTaskInvoked = mTaskInvoked.get();
		if (!wasTaskInvoked) {
			postResult(result);
		}
	}

	private Result postResult(Result result) {
		@SuppressWarnings("unchecked") Message message = getHandler().obtainMessage(MESSAGE_POST_RESULT, new AsyncTaskResult<Result>(this, result));
		message.sendToTarget();
		return result;
	}

	/**
	 * Returns the current status of this task.
	 *
	 * @return The current status.
	 */
	public final Status getStatus() {
		return mStatus;
	}

	/**
	 * Override this method to perform a computation on a background thread. The
	 * specified parameters are the parameters passed to {@link #execute}
	 * by the caller of this task.
	 * <p>
	 * This method can call {@link #publishProgress} to publish updates
	 * on the UI thread.
	 *
	 * @param params The parameters of the task.
	 * @return A result, defined by the subclass of this task.
	 * @see #onPreExecute()
	 * @see #onPostExecute
	 * @see #publishProgress
	 */
	@WorkerThread
	protected abstract Result doInBackground(Params... params);

	/**
	 * Runs on the UI thread before {@link #doInBackground}.
	 *
	 * @see #onPostExecute
	 * @see #doInBackground
	 */
	@MainThread
	protected void onPreExecute() {
	}

	/**
	 * <p>Runs on the UI thread after {@link #doInBackground}. The
	 * specified result is the value returned by {@link #doInBackground}.</p>
	 * <p>
	 * <p>This method won't be invoked if the task was cancelled.</p>
	 *
	 * @param result The result of the operation computed by {@link #doInBackground}.
	 * @see #onPreExecute
	 * @see #doInBackground
	 * @see #onCancelled(Object)
	 */
	@SuppressWarnings({"UnusedDeclaration"})
	@MainThread
	protected void onPostExecute(Result result) {
	}

	/**
	 * Runs on the UI thread after {@link #publishProgress} is invoked.
	 * The specified values are the values passed to {@link #publishProgress}.
	 *
	 * @param values The values indicating progress.
	 * @see #publishProgress
	 * @see #doInBackground
	 */
	@SuppressWarnings({"UnusedDeclaration"})
	@MainThread
	protected void onProgressUpdate(Progress... values) {
	}

	/**
	 * <p>Runs on the UI thread after {@link #cancel(boolean)} is invoked and
	 * {@link #doInBackground(Object[])} has finished.</p>
	 * <p>
	 * <p>The default implementation simply invokes {@link #onCancelled()} and
	 * ignores the result. If you write your own implementation, do not call
	 * <code>super.onCancelled(result)</code>.</p>
	 *
	 * @param result The result, if any, computed in
	 *               {@link #doInBackground(Object[])}, can be null
	 * @see #cancel(boolean)
	 * @see #isCancelled()
	 */
	@SuppressWarnings({"UnusedParameters"})
	@MainThread
	protected void onCancelled(Result result) {
		onCancelled();
	}

	/**
	 * <p>Applications should preferably override {@link #onCancelled(Object)}.
	 * This method is invoked by the default implementation of
	 * {@link #onCancelled(Object)}.</p>
	 * <p>
	 * <p>Runs on the UI thread after {@link #cancel(boolean)} is invoked and
	 * {@link #doInBackground(Object[])} has finished.</p>
	 *
	 * @see #onCancelled(Object)
	 * @see #cancel(boolean)
	 * @see #isCancelled()
	 */
	@MainThread
	protected void onCancelled() {
	}

	/**
	 * Returns <tt>true</tt> if this task was cancelled before it completed
	 * normally. If you are calling {@link #cancel(boolean)} on the task,
	 * the value returned by this method should be checked periodically from
	 * {@link #doInBackground(Object[])} to end the task as soon as possible.
	 *
	 * @return <tt>true</tt> if task was cancelled before it completed
	 * @see #cancel(boolean)
	 */
	public final boolean isCancelled() {
		return mCancelled.get();
	}

	/**
	 * <p>Attempts to cancel execution of this task.  This attempt will
	 * fail if the task has already completed, already been cancelled,
	 * or could not be cancelled for some other reason. If successful,
	 * and this task has not started when <tt>cancel</tt> is called,
	 * this task should never run. If the task has already started,
	 * then the <tt>mayInterruptIfRunning</tt> parameter determines
	 * whether the thread executing this task should be interrupted in
	 * an attempt to stop the task.</p>
	 * <p>
	 * <p>Calling this method will result in {@link #onCancelled(Object)} being
	 * invoked on the UI thread after {@link #doInBackground(Object[])}
	 * returns. Calling this method guarantees that {@link #onPostExecute(Object)}
	 * is never invoked. After invoking this method, you should check the
	 * value returned by {@link #isCancelled()} periodically from
	 * {@link #doInBackground(Object[])} to finish the task as early as
	 * possible.</p>
	 *
	 * @param mayInterruptIfRunning <tt>true</tt> if the thread executing this
	 *                              task should be interrupted; otherwise, in-progress tasks are allowed
	 *                              to complete.
	 * @return <tt>false</tt> if the task could not be cancelled,
	 * typically because it has already completed normally;
	 * <tt>true</tt> otherwise
	 * @see #isCancelled()
	 * @see #onCancelled(Object)
	 */
	public final boolean cancel(boolean mayInterruptIfRunning) {
		mCancelled.set(true);
		return mFuture.cancel(mayInterruptIfRunning);
	}

	/**
	 * Waits if necessary for the computation to complete, and then
	 * retrieves its result.
	 *
	 * @return The computed result.
	 * @throws CancellationException If the computation was cancelled.
	 * @throws ExecutionException    If the computation threw an exception.
	 * @throws InterruptedException  If the current thread was interrupted
	 *                               while waiting.
	 */
	public final Result get() throws InterruptedException, ExecutionException {
		return mFuture.get();
	}

	/**
	 * Waits if necessary for at most the given time for the computation
	 * to complete, and then retrieves its result.
	 *
	 * @param timeout Time to wait before cancelling the operation.
	 * @param unit    The time unit for the timeout.
	 * @return The computed result.
	 * @throws CancellationException If the computation was cancelled.
	 * @throws ExecutionException    If the computation threw an exception.
	 * @throws InterruptedException  If the current thread was interrupted
	 *                               while waiting.
	 * @throws TimeoutException      If the wait timed out.
	 */
	public final Result get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		return mFuture.get(timeout, unit);
	}

	/**
	 * Executes the task with the specified parameters. The task returns
	 * itself (this) so that the caller can keep a reference to it.
	 * <p>
	 * <p>Note: this function schedules the task on a queue for a single background
	 * thread or pool of threads depending on the platform version.  When first
	 * introduced, AsyncTasks were executed serially on a single background thread.
	 * Starting with {@link android.os.Build.VERSION_CODES#DONUT}, this was changed
	 * to a pool of threads allowing multiple tasks to operate in parallel. Starting
	 * {@link android.os.Build.VERSION_CODES#HONEYCOMB}, tasks are back to being
	 * executed on a single thread to avoid common application errors caused
	 * by parallel execution.  If you truly want parallel execution, you can use
	 * the {@link #executeOnExecutor} version of this method
	 * with {@link #THREAD_POOL_EXECUTOR}; however, see commentary there for warnings
	 * on its use.
	 * <p>
	 * <p>This method must be invoked on the UI thread.
	 *
	 * @param params The parameters of the task.
	 * @return This instance of AsyncTask.
	 * @throws IllegalStateException If {@link #getStatus()} returns either
	 *                               {@link AsyncTask.Status#RUNNING} or {@link AsyncTask.Status#FINISHED}.
	 * @see #executeOnExecutor(java.util.concurrent.Executor, Object[])
	 * @see #execute(Runnable)
	 */
	@MainThread
	public final AsyncTask<Params, Progress, Result> execute(Params... params) {
		return executeOnExecutor(sDefaultExecutor, params);
	}

	/**
	 * Executes the task with the specified parameters. The task returns
	 * itself (this) so that the caller can keep a reference to it.
	 * <p>
	 * <p>This method is typically used with {@link #THREAD_POOL_EXECUTOR} to
	 * allow multiple tasks to run in parallel on a pool of threads managed by
	 * AsyncTask, however you can also use your own {@link Executor} for custom
	 * behavior.
	 * <p>
	 * <p><em>Warning:</em> Allowing multiple tasks to run in parallel from
	 * a thread pool is generally <em>not</em> what one wants, because the order
	 * of their operation is not defined.  For example, if these tasks are used
	 * to modify any state in common (such as writing a file due to a button click),
	 * there are no guarantees on the order of the modifications.
	 * Without careful work it is possible in rare cases for the newer version
	 * of the data to be over-written by an older one, leading to obscure data
	 * loss and stability issues.  Such changes are best
	 * executed in serial; to guarantee such work is serialized regardless of
	 * platform version you can use this function with {@link #SERIAL_EXECUTOR}.
	 * <p>
	 * <p>This method must be invoked on the UI thread.
	 *
	 * @param exec   The executor to use.  {@link #THREAD_POOL_EXECUTOR} is available as a
	 *               convenient process-wide thread pool for tasks that are loosely coupled.
	 * @param params The parameters of the task.
	 * @return This instance of AsyncTask.
	 * @throws IllegalStateException If {@link #getStatus()} returns either
	 *                               {@link AsyncTask.Status#RUNNING} or {@link AsyncTask.Status#FINISHED}.
	 * @see #execute(Object[])
	 */
	@MainThread
	public final AsyncTask<Params, Progress, Result> executeOnExecutor(Executor exec, Params... params) {
		if (mStatus != Status.PENDING) {
			switch (mStatus) {
				case RUNNING:
					throw new IllegalStateException("Cannot execute task:" + " the task is already running.");
				case FINISHED:
					throw new IllegalStateException("Cannot execute task:" + " the task has already been executed " + "(a task can be executed only once)");
			}
		}

		mStatus = Status.RUNNING;

		onPreExecute();

		mWorker.mParams = params;
		exec.execute(mFuture);

		return this;
	}

	/**
	 * Convenience version of {@link #execute(Object...)} for use with
	 * a simple Runnable object. See {@link #execute(Object[])} for more
	 * information on the order of execution.
	 *
	 * @see #execute(Object[])
	 * @see #executeOnExecutor(java.util.concurrent.Executor, Object[])
	 */
	@MainThread
	public static void execute(Runnable runnable) {
		sDefaultExecutor.execute(runnable);
	}

	/**
	 * This method can be invoked from {@link #doInBackground} to
	 * publish updates on the UI thread while the background computation is
	 * still running. Each call to this method will trigger the execution of
	 * {@link #onProgressUpdate} on the UI thread.
	 * <p>
	 * {@link #onProgressUpdate} will not be called if the task has been
	 * canceled.
	 *
	 * @param values The progress values to update the UI with.
	 * @see #onProgressUpdate
	 * @see #doInBackground
	 */
	@WorkerThread
	protected final void publishProgress(Progress... values) {
		if (!isCancelled()) {
			getHandler().obtainMessage(MESSAGE_POST_PROGRESS, new AsyncTaskResult<Progress>(this, values)).sendToTarget();
		}
	}

	private void finish(Result result) {
		if (isCancelled()) {
			onCancelled(result);
		} else {
			onPostExecute(result);
		}
		mStatus = Status.FINISHED;
	}

	private static class InternalHandler extends Handler {
		public InternalHandler(Looper looper) {
			super(looper);
		}

		@SuppressWarnings({"unchecked", "RawUseOfParameterizedType"})
		@Override
		public void handleMessage(Message msg) {
			AsyncTaskResult<?> result = (AsyncTaskResult<?>) msg.obj;
			switch (msg.what) {
				case MESSAGE_POST_RESULT:
					// There is only one result
					result.mTask.finish(result.mData[0]);
					break;
				case MESSAGE_POST_PROGRESS:
					result.mTask.onProgressUpdate(result.mData);
					break;
			}
		}
	}

	private static abstract class WorkerRunnable<Params, Result> implements Callable<Result> {
		Params[] mParams;
	}

	@SuppressWarnings({"RawUseOfParameterizedType"})
	private static class AsyncTaskResult<Data> {
		final AsyncTask mTask;
		final Data[] mData;

		AsyncTaskResult(AsyncTask task, Data... data) {
			mTask = task;
			mData = data;
		}
	}
}

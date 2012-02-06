/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.internal.server.core.tasks;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.orion.server.core.LogHelper;
import org.eclipse.orion.server.core.ServerStatus;
import org.eclipse.orion.server.core.resources.UniversalUniqueIdentifier;
import org.eclipse.orion.server.core.tasks.ITaskCanceler;
import org.eclipse.orion.server.core.tasks.ITaskService;
import org.eclipse.orion.server.core.tasks.TaskDoesNotExistException;
import org.eclipse.orion.server.core.tasks.TaskInfo;
import org.eclipse.orion.server.core.tasks.TaskModificationListener;
import org.eclipse.orion.server.core.tasks.TaskOperationException;

/**
 * A concrete implementation of the {@link ITaskService}.
 */
public class TaskService implements ITaskService {

	TaskStore store;
	private Map<String, ITaskCanceler> taskCancelers = new HashMap<String, ITaskCanceler>();
	private Set<TaskModificationListener> taskListeners = new HashSet<TaskModificationListener>();
	private Map<String, List<TaskDeletion>> taskDeletions = new HashMap<String, List<TaskDeletion>>();

	private class TaskDeletion {
		public final Date deletionDate;
		public final String taskId;

		public TaskDeletion(Date deletionDate, String taskId) {
			super();
			this.deletionDate = deletionDate;
			this.taskId = taskId;
		}
	}

	private class TasksNotificationJob extends Job {

		private String userId;
		private Date modificationDate;

		public TasksNotificationJob(String userId, Date modificationDate) {
			super("Notyfing task listeners");
			this.userId = userId;
			this.modificationDate = modificationDate;
		}

		@Override
		protected IStatus run(IProgressMonitor monitor) {
			synchronized (taskListeners) {
				for (TaskModificationListener listener : taskListeners) {
					listener.tasksModified(userId, modificationDate);
				}
			}
			return Status.OK_STATUS;
		}

	}

	private class DeletedTasksNotificationJob extends Job {

		private String userId;
		private Date deletionDate;
		private Collection<String> tasks;

		public DeletedTasksNotificationJob(String userId, Date deletionDate, Collection<String> tasks) {
			super("Notyfing task listeners");
			this.userId = userId;
			this.deletionDate = deletionDate;
			this.tasks = tasks;
		}

		@Override
		protected IStatus run(IProgressMonitor monitor) {
			synchronized (taskListeners) {
				for (TaskModificationListener listener : taskListeners) {
					listener.tasksDeleted(userId, tasks, deletionDate);
				}
			}
			return Status.OK_STATUS;
		}
	}

	public TaskService(IPath baseLocation) {
		store = new TaskStore(baseLocation.toFile());
		cleanUpTasks();
	}

	private void cleanUpTasks() {
		List<String> allTasks = store.readAllTasks();
		Calendar monthAgo = Calendar.getInstance();
		monthAgo.add(Calendar.MONTH, -1);
		for (String taskString : allTasks) {
			TaskInfo task = TaskInfo.fromJSON(taskString);
			if(task==null){
				continue;
			}
			if (task.isRunning()) {//mark all running tasks as failed due to server restart
				task.done(new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Task could not be completed due to server restart", null));
				updateTask(task);
			}
			if(task.getModified().before(monthAgo.getTime())){ //remove tasks older than a month
				try {
					removeTask(task.getUserId(), task.getTaskId());
				} catch (TaskOperationException e) {
					LogHelper.log(e); //should never happen. All running tasks where already stopped. 
				}
			}
		}
	}

	public TaskInfo createTask(String taskName, String userId, boolean isIdempotent) {
		return createTask(taskName, userId, null, isIdempotent);
	}

	private TaskInfo internalRemoveTask(String userId, String id) throws TaskOperationException {
		TaskInfo task = getTask(userId, id);
		if (task == null)
			throw new TaskDoesNotExistException(id);
		if (task.isRunning())
			throw new TaskOperationException("Cannot remove a task that is running. Try to cancel first");
		if (!store.removeTask(userId, id))
			throw new TaskOperationException("Task could not be removed");
		taskCancelers.remove(id);
		if (!taskDeletions.containsKey(userId)) {
			taskDeletions.put(userId, new ArrayList<TaskService.TaskDeletion>());
		}
		taskDeletions.get(userId).add(new TaskDeletion(new Date(), id));
		return task;
	}

	public void removeTask(String userId, String id) throws TaskOperationException {
		internalRemoveTask(userId, id);
		Set<String> tasks = new HashSet<String>();
		tasks.add(id);
		notifyDeletionListeners(userId, new Date(), tasks);
	}

	public void removeCompletedTasks(String userId) {
		Set<String> tasks = new HashSet<String>();
		for (TaskInfo task : getTasks(userId)) {
			if (!task.isRunning()) {
				try {
					internalRemoveTask(userId, task.getTaskId());
					tasks.add(task.getTaskId());
				} catch (TaskOperationException e) {
					LogHelper.log(e);
				}
			}
		}
		notifyDeletionListeners(userId, new Date(), tasks);
	}

	public TaskInfo createTask(String taskName, String userId, ITaskCanceler taskCanceler, boolean isIdempotent) {
		TaskInfo task = new TaskInfo(userId, new UniversalUniqueIdentifier().toBase64String(), isIdempotent);
		task.setName(taskName);
		if (taskCanceler != null) {
			taskCancelers.put(task.getTaskId(), taskCanceler);
			task.setCanBeCanceled(true);
		}
		store.writeTask(userId, task.getTaskId(), task.toJSON().toString());
		notifyListeners(userId, task.getModified());
		return task;
	}

	private void notifyListeners(String userId, Date modificationDate) {
		new TasksNotificationJob(userId, modificationDate).schedule();
	}

	private void notifyDeletionListeners(String userId, Date deletionDate, Collection<String> tasks) {
		new DeletedTasksNotificationJob(userId, deletionDate, tasks).schedule();
	}

	public TaskInfo getTask(String userId, String id) {
		String taskString = store.readTask(userId, id);
		if (taskString == null)
			return null;
		TaskInfo info = TaskInfo.fromJSON(taskString);
		if (taskCancelers.get(id) != null)
			info.setCanBeCanceled(true);
		return info;
	}

	public void updateTask(TaskInfo task) {
		task.setModified(new Date());
		store.writeTask(task.getUserId(), task.getTaskId(), task.toJSON().toString());
		notifyListeners(task.getUserId(), task.getModified());
	}

	public List<TaskInfo> getTasks(String userId) {
		return getTasks(userId, null, false);
	}

	public void cancelTask(TaskInfo task) throws TaskOperationException {
		ITaskCanceler taskCanceler = taskCancelers.get(task.getTaskId());
		if (taskCanceler == null) {
			throw new TaskOperationException("Task does not support canceling.");
		}
		taskCanceler.cancelTask();
	}

	public List<TaskInfo> getTasks(String userId, Date modifiedSince, boolean running) {
		List<TaskInfo> tasks = new ArrayList<TaskInfo>();
		for (String taskString : store.readAllTasks(userId)) {
			TaskInfo info = TaskInfo.fromJSON(taskString);
			if (modifiedSince != null) {
				if (info.getModified().getTime() < modifiedSince.getTime()) {
					continue;
				}
			}
			if (running && !info.isRunning()) {
				continue;
			}

			ITaskCanceler taskCanceler = taskCancelers.get(info.getTaskId());
			if (taskCanceler != null)
				info.setCanBeCanceled(true);
			tasks.add(info);
		}
		return tasks;
	}

	public void addTaskModyficationListener(TaskModificationListener listener) {
		synchronized (taskListeners) {
			this.taskListeners.add(listener);
		}
	}

	public synchronized void removeTaskModyficationListener(TaskModificationListener listener) {
		synchronized (taskListeners) {
			this.taskListeners.remove(listener);
		}
	}

	public Collection<String> getTasksDeletedSince(String userId, Date deletedSince) {
		Set<String> deletedTasks = new HashSet<String>();
		List<TaskDeletion> taskDeletionsList = taskDeletions.get(userId);
		if (taskDeletionsList == null) {
			return deletedTasks;
		}
		for (int i = taskDeletionsList.size() - 1; i > 0; i--) {
			if (taskDeletionsList.get(i).deletionDate.before(deletedSince)) {
				return deletedTasks;
			}
			deletedTasks.add(taskDeletionsList.get(i).taskId);
		}
		return deletedTasks;
	}

}

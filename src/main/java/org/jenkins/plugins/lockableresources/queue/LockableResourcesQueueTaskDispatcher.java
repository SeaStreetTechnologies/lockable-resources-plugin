/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourceStratos;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

import com.seastreet.client.api.StratOSControllerAPI;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

	static final Logger LOGGER = Logger
			.getLogger(LockableResourcesQueueTaskDispatcher.class.getName());

	@Override
	public CauseOfBlockage canRun(Queue.Item item) {
		// Skip locking for multiple configuration projects,
		// only the child jobs will actually lock resources.
		if (item.task instanceof MatrixProject)
			return null;

		Job<?, ?> project = Utils.getProject(item);
		if (project == null)
			return null;	
		List<LockableResource> localResource = LockableResourcesManager.get().getlocalResources();
		List<LockableResource> allResource = LockableResourcesManager.get().getResources();
		StratOSControllerAPI stratosAPI = LockableResourceStratos.getControllerAPI();
		
		RequiredResourcesProperty property = Utils.getProperty(project);
		String name = property.getResourceNames();
		
		// If stratos is healthy but the resource is not a valid resource
		if(LockableResourceStratos.isStratosHealthy(stratosAPI) && LockableResourcesManager.get().fromName(name,allResource) == null){
			LOGGER.fine(name + " is not a lockable resource");
			return new BecauseResourcesLocked("Could not find resource with name: " + name);	
		} // If stratos is not healthy and the resource is not in local resources
		else if (!LockableResourceStratos.isStratosHealthy(stratosAPI) && LockableResourcesManager.get().fromName(name,localResource) == null){
			LOGGER.fine(stratosAPI.getUrl() + " is not healthy. Unable to lock " + name);
			return new BecauseResourcesLocked(stratosAPI.getUrl() + " is not healthy. Unable to lock " + name);
		}
		
		LockableResourcesStruct resources = Utils.requiredResources(project);
		if (resources == null ||
			(resources.required.isEmpty() && resources.label.isEmpty())) {
			return null;
		}

		int resourceNumber;
		try {
			resourceNumber = Integer.parseInt(resources.requiredNumber);
		} catch (NumberFormatException e) {
			resourceNumber = 0;
		}

		LOGGER.finest(project.getName() +
			" trying to get resources with these details: " + resources);

		if (resourceNumber > 0 || !resources.label.isEmpty()) {
			Map<String, Object> params = new HashMap<String, Object>();
			if (item.task instanceof MatrixConfiguration) {
			    MatrixConfiguration matrix = (MatrixConfiguration) item.task;
			    params.putAll(matrix.getCombination());
			}

			List<LockableResource> selected = LockableResourcesManager.get().queue(
					resources,
					item.getId(),
					project.getFullName(),
					resourceNumber,
					params,
					LOGGER);

			if (selected != null) {
				LOGGER.finest(project.getName() + " reserved resources " + selected);
				return null;
			} else {
				LOGGER.finest(project.getName() + " waiting for resources");
				return new BecauseResourcesLocked(resources);
			}

		} else {
			if (LockableResourcesManager.get().queue(resources.required, item.getId())) {
				LOGGER.finest(project.getName() + " reserved resources " + resources.required);
				return null;
			} else {
				LOGGER.finest(project.getName() + " waiting for resources "
					+ resources.required);
				return new BecauseResourcesLocked(resources);
			}
		}
	}

	public static class BecauseResourcesLocked extends CauseOfBlockage {

		private final LockableResourcesStruct rscStruct;
		private String reason = null;

		public BecauseResourcesLocked(LockableResourcesStruct r) {
			this.rscStruct = r;
		}
		
		public BecauseResourcesLocked(String reason) {
			rscStruct = null;
			this.reason = reason;
		}

		@Override
		public String getShortDescription() {
			if(reason!=null)
				return reason;
			if (this.rscStruct.label.isEmpty())
				return "Waiting for resources " + rscStruct.required.toString();
			else
				return "Waiting for resources with label " + rscStruct.label;
		}
	}

}

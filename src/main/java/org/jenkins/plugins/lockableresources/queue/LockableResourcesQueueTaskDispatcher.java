/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.util.ArrayList;
import java.util.Arrays;
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
		
		RequiredResourcesProperty property = Utils.getProperty(project);
		// If the project does not require any lockable resources then return null
		if(property == null)
			return null;
		
		// Get all the resources that the job needs.
		String names = property.getResourceNames();
		List<String> jobResources = new ArrayList<String>(Arrays.asList(names.split("\\s+")));

		if (!LockableResourcesManager.get().compareLocalResources(jobResources)){
			// There are some remote resources. Check to see if there is a valid config
			StratOSControllerAPI stratosAPI = LockableResourceStratos.getControllerAPI();
			if (stratosAPI != null) {
				// Check to make sure that stratos is healthy
				if (!LockableResourceStratos.isStratosHealthy(stratosAPI)){
					LOGGER.fine(stratosAPI.getUrl() + " is not healthy.");
					return new BecauseResourcesLocked(stratosAPI.getUrl() + " is not healthy.");
				}
			}else{
				LOGGER.fine("The lockable resource plugin is misconfigured.  Could not get stratos API.");
				return new BecauseResourcesLocked("The lockable resource plugin is misconfigured.  Could not get stratos API.");
			}
		}
		
		// Verify that all resources are valid and update resources reservations.
		List<LockableResource> allResources = LockableResourcesManager.get().getResources(true);
		for (String resource: jobResources) {
			LOGGER.fine("Checking to make sure " + resource + " is a valid resource.");
			if (LockableResourcesManager.get().fromName(resource,allResources) == null) {
				// This is not a valid resource.  Block the job
				LOGGER.fine(resource + " is not a lockable resource");
				return new BecauseResourcesLocked("Could not find resource with name: " + resource);
			}
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

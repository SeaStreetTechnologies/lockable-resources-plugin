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
import java.util.List;
import java.util.Queue;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourceStratos;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.actions.ResourceVariableNameAction;
import com.seastreet.client.api.StratOSControllerAPI;

import hudson.Extension;
import hudson.matrix.MatrixBuild;
import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.Cause.UserIdCause;
import hudson.model.listeners.RunListener;

@Extension
public class LockRunListener extends RunListener<Run<?, ?>> {

	static final String LOG_PREFIX = "[lockable-resources]";
	static final Logger LOGGER = Logger.getLogger(LockRunListener.class
			.getName());
	public static String userId = null;

	@Override
	public void onStarted(Run<?, ?> build, TaskListener listener) {
		// Skip locking for multiple configuration projects,
		// only the child jobs will actually lock resources.
		if (build instanceof MatrixBuild)
			return;
		// If the job is started by a timer or chain the user will be null
		try{
			userId = build.getCause(UserIdCause.class).getUserId();
		}catch(Exception e){
			System.out.println("Error getting the user. " + e);
			userId = null;
		}

		if (build instanceof AbstractBuild) {
			Job<?, ?> proj = Utils.getProject(build);
			List<LockableResource> required = new ArrayList<LockableResource>();
			if (proj != null) {
				LockableResourcesStruct resources = Utils.requiredResources(proj);
				if (resources != null) {
					if (resources.requiredNumber != null || !resources.label.isEmpty()) {
						required = LockableResourcesManager.get().
								getResourcesFromProject(proj.getFullName());
					} else {
						required = resources.required;
					}
					if (LockableResourcesManager.get().lock(required, build, null)) {
						build.addAction(LockedResourcesBuildAction
								.fromResources(required));
						listener.getLogger().printf("%s acquired lock on %s\n",
								LOG_PREFIX, required);
						LOGGER.fine(build.getFullDisplayName()
								+ " acquired lock on " + required);
						if (resources.requiredVar != null) {
							build.addAction(new ResourceVariableNameAction(new StringParameterValue(
									resources.requiredVar,
									required.toString().replaceAll("[\\]\\[]", ""))));
						}
					} else {
						listener.getLogger().printf("%s failed to lock %s\n",
								LOG_PREFIX, required);
						LOGGER.fine(build.getFullDisplayName() + " failed to lock "
								+ required);
					}
				}
			}
		}

		return;
	}

	@Override
	public void onCompleted(Run<?, ?> build, TaskListener listener) {
		
		userId = null;
		// Skip unlocking for multiple configuration projects,
		// only the child jobs will actually unlock resources.
		if (build instanceof MatrixBuild)
			return;

		// obviously project name cannot be obtained here
		List<LockableResource> required = LockableResourcesManager.get()
				.getResourcesFromBuild(build);
		if (required.size() > 0) {
			LockableResourcesManager.get().unlock(required, build);
			listener.getLogger().printf("%s released lock on %s\n",
					LOG_PREFIX, required);
			LOGGER.fine(build.getFullDisplayName() + " released lock on "
					+ required);
		}
		// There could be stratos resources and the server is down.
		Queue<String> downStratosBuilds = LockableResourcesManager.get().getDownStratosBuilds();

		StratOSControllerAPI stratosAPI = LockableResourceStratos.getControllerAPI();
		if (stratosAPI != null) {
			if (!LockableResourceStratos.isStratosHealthy(stratosAPI)){
				LOGGER.fine(stratosAPI.getUrl() + " is not healthy.");
				String buildID = build.getExternalizableId();
				downStratosBuilds.add(buildID);
				LOGGER.fine(buildID + " was added to the queue of builds that finished while stratos was down.");
				// save the id that was just added
				LockableResourcesManager.get().save();
			}else if (!downStratosBuilds.isEmpty()){
				// Since the server is healthy and there is a queue of resource we should process it.
				LOGGER.fine(stratosAPI.getUrl() + " is healthy process the queue.");
				LockableResourcesManager.get().processDownQueue();
			}
		}
	}

	@Override
	public void onDeleted(Run<?, ?> build) {
		
		userId = null;
		// Skip unlocking for multiple configuration projects,
		// only the child jobs will actually unlock resources.
		if (build instanceof MatrixBuild)
			return;

		List<LockableResource> required = LockableResourcesManager.get()
				.getResourcesFromBuild(build);
		if (required.size() > 0) {
			LockableResourcesManager.get().unlock(required, build);
			LOGGER.fine(build.getFullDisplayName() + " released lock on "
					+ required);
		}
	}
	
	public static String getUserId(){
		return userId;
	}

}

/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.Run;
import hudson.util.Secret;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {

	@Deprecated
	private transient int defaultPriority;
	@Deprecated
	private transient String priorityParameterName;
	private List<LockableResource> localResources;
	
	private String stratosURL;
	private String username;
	private String password;
	private List<LockableResource> resources;

	/**
	 * Only used when this lockable resource is tried to be locked by {@link LockStep},
	 * otherwise (freestyle builds) regular Jenkins queue is used.
	 */
	private List<QueuedContextStruct> queuedContexts = new ArrayList<QueuedContextStruct>();

	public LockableResourcesManager() {

		localResources = new ArrayList<LockableResource>();
		stratosURL = new String();
		username = new String();
		password = new String();
		resources = new ArrayList<LockableResource>();
		load();	
		moveOriginalResources();
		
	}
	
	public void moveOriginalResources(){
		
		List<LockableResource> originalResources = getOriginalResourcesFromConfigFile();
		if (!originalResources.isEmpty()){
			// There are old resources.  We need to make them local resources
			for (LockableResource r : originalResources) {
				// add all the original resources as local resources
				localResources.add(r);
			}
			// Remove the original resources
			originalResources.removeAll(localResources);
			// Now that the original resources are local resources save the config.
			save();
		}
	}

	public List<LockableResource> getResources() {
		List<LockableResource> resources = new ArrayList<LockableResource>();

		List<LockableResourceStratos> sr = LockableResourceStratos.getStratosResources();
		if(sr != null)
			resources.addAll(sr);
		resources.addAll(localResources);

		return resources;

	}
	
	
	public List<LockableResource> getResourcesFromProject(String fullName) {
		List<LockableResource> matching = new ArrayList<LockableResource>();
		for (LockableResource r : getResources()) {
			String rName = r.getQueueItemProject();
			if (rName != null && rName.equals(fullName)) {
				matching.add(r);
			}
		}
		return matching;
	}

	public List<LockableResource> getResourcesFromBuild(Run<?, ?> build) {
		List<LockableResource> matching = new ArrayList<LockableResource>();
		for (LockableResource r : getResources()) {
			Run<?, ?> rBuild = r.getBuild();
			if (rBuild != null && rBuild == build) {
				matching.add(r);
			}
		}
		return matching;
	}

	public Boolean isValidLabel(String label)
	{
		return label.startsWith(LockableResource.GROOVY_LABEL_MARKER)
				|| this.getAllLabels().contains(label);
	}

	public Set<String> getAllLabels()
	{
		Set<String> labels = new HashSet<String>();
		for (LockableResource r : this.getResources()) {
			String rl = r.getLabels();
			if (rl == null || "".equals(rl))
				continue;
			labels.addAll(Arrays.asList(rl.split("\\s+")));
		}
		return labels;
	}

	public int getFreeResourceAmount(String label)
	{
		int free = 0;
		for (LockableResource r : this.getResources()) {
			if (r.isLocked() || r.isQueued() || r.isReserved())
				continue;
			if (Arrays.asList(r.getLabels().split("\\s+")).contains(label))
				free += 1;
		}
		return free;
	}

	public List<LockableResource> getResourcesWithLabel(String label,
			Map<String, Object> params) {
		List<LockableResource> found = new ArrayList<LockableResource>();
		for (LockableResource r : this.getResources()) {
			if (r.isValidLabel(label, params))
				found.add(r);
		}
		return found;
	}

	public LockableResource fromName(String resourceName) {
		if (resourceName != null) {
			for (LockableResource r : getResources()) {
				if (resourceName.equals(r.getName()))
					return r;
			}
		}
		return null;
	}
	
	public LockableResource fromName(String resourceName, List<LockableResource> resourceList) {
		if (resourceName != null) {
			for (LockableResource r : resourceList) {
				if (resourceName.equals(r.getName()))
					return r;
			}
		}
		return null;
	}

	public synchronized boolean queue(List<LockableResource> resources,
			long queueItemId) {
		for (LockableResource r : resources)
			if (r.isReserved() || r.isQueued(queueItemId) || r.isLocked())
				return false;
		for (LockableResource r : resources)
			r.setQueued(queueItemId);
		return true;
	}

	public synchronized List<LockableResource> queue(LockableResourcesStruct requiredResources,
	                                                 long queueItemId,
	                                                 String queueItemProject,
	                                                 int number,  // 0 means all
	                                                 Map<String, Object> params,
	                                                 Logger log) {
		List<LockableResource> selected = new ArrayList<LockableResource>();

		if (!checkCurrentResourcesStatus(selected, queueItemProject, queueItemId, log)) {
			// The project has another buildable item waiting -> bail out
			log.log(Level.FINEST, "{0} has another build waiting resources." +
			        " Waiting for it to proceed first.",
			        new Object[]{queueItemProject});
			return null;
		}

		List<LockableResource> candidates = new ArrayList<LockableResource>();
		if (requiredResources.label != null && requiredResources.label.isEmpty()) {
			candidates = requiredResources.required;
		} else {
			candidates = getResourcesWithLabel(requiredResources.label, params);
		}

		for (LockableResource rs : candidates) {
			if (number != 0 && (selected.size() >= number))
				break;
			if (!rs.isReserved() && !rs.isLocked() && !rs.isQueued())
				selected.add(rs);
		}

		// if did not get wanted amount or did not get all
		int required_amount = number == 0 ? candidates.size() : number;

		if (selected.size() != required_amount) {
			log.log(Level.FINEST, "{0} found {1} resource(s) to queue." +
			        "Waiting for correct amount: {2}.",
			        new Object[]{queueItemProject, selected.size(), required_amount});
			// just to be sure, clean up
			for (LockableResource x : getResources()) {
				if (x.getQueueItemProject() != null &&
				    x.getQueueItemProject().equals(queueItemProject))
					x.unqueue();
			}
			return null;
		}

		for (LockableResource rsc : selected) {
			rsc.setQueued(queueItemId, queueItemProject);
		}
		return selected;
	}

	// Adds already selected (in previous queue round) resources to 'selected'
	// Return false if another item queued for this project -> bail out
	private boolean checkCurrentResourcesStatus(List<LockableResource> selected,
	                                            String project,
	                                            long taskId,
	                                            Logger log) {
		for (LockableResource r : getResources()) {
			// This project might already have something in queue
			String rProject = r.getQueueItemProject();
			if (rProject != null && rProject.equals(project)) {
				if (r.isQueuedByTask(taskId)) {
					// this item has queued the resource earlier
					selected.add(r);
				} else {
					// The project has another buildable item waiting -> bail out
					log.log(Level.FINEST, "{0} has another build " +
						"that already queued resource {1}. Continue queueing.",
						new Object[]{project, r});
					return false;
				}
			}
		}
		return true;
	}
	
	public synchronized boolean lock(List<LockableResource> resources, Run<?, ?> build, @Nullable StepContext context) {
		return lock(resources, build, context, null, false);
	}

	/**
	 * Try to lock the resource and return true if locked.
	 */
	public synchronized boolean lock(List<LockableResource> resources,
			Run<?, ?> build, @Nullable StepContext context, @Nullable String logmessage, boolean inversePrecedence) {
		boolean needToWait = false;
		for (LockableResource r : resources) {
			if (r.isReserved() || r.isLocked()) {
				needToWait = true;
				break;
			}
		}
		if (!needToWait) {
			for (LockableResource r : resources) {
				r.unqueue();
				r.setBuild(build);
			}
			if (context != null) {
				// since LockableResource contains transient variables, they cannot be correctly serialized
				// hence we use their unique resource names
				List<String> resourceNames = new ArrayList<String>();
				for (LockableResource resource : resources) {
					resourceNames.add(resource.getName());
				}
				LockStepExecution.proceed(resourceNames, context, logmessage, inversePrecedence);
			}
		}
		save();
		return !needToWait;
	}
	
	private synchronized void freeResources(List<String> unlockResourceNames, @Nullable Run<?, ?> build) {
		for (String unlockResourceName : unlockResourceNames) {
			for (LockableResource resource : this.getResources()) {
				if (resource.getName().equals(unlockResourceName)) {
					if (build == null || (resource.getBuild() != null && build.getExternalizableId().equals(resource.getBuild().getExternalizableId()))) {
						// No more contexts, unlock resource
						resource.unqueue();
						resource.setBuild(null);
					}
				}
			}
		}
	}

	public synchronized void unlock(List<LockableResource> resourcesToUnLock, @Nullable Run<?, ?> build) {
		unlock(resourcesToUnLock, build, false);
	}

	public synchronized void unlock(@Nullable List<LockableResource> resourcesToUnLock,
									@Nullable Run<?, ?> build, boolean inversePrecedence) {
		List<String> resourceNamesToUnLock = new ArrayList<String>();
		if (resourcesToUnLock != null) {
			for (LockableResource r : resourcesToUnLock) {
				resourceNamesToUnLock.add(r.getName());
			}
		}

		this.unlockNames(resourceNamesToUnLock, build, inversePrecedence);
	}

	public synchronized void unlockNames(@Nullable List<String> resourceNamesToUnLock, @Nullable Run<?, ?> build, boolean inversePrecedence) {
		// make sure there is a list of resource names to unlock
		if (resourceNamesToUnLock == null || (resourceNamesToUnLock.size() == 0)) {
			return;
		}

		// check if there are resources which can be unlocked (and shall not be unlocked)
		List<LockableResource> requiredResourceForNextContext = null;
		QueuedContextStruct nextContext = this.getNextQueuedContext(resourceNamesToUnLock, inversePrecedence);

		// no context is queued which can be started once these resources are free'd.
		if (nextContext == null) {
			this.freeResources(resourceNamesToUnLock, build);
			save();
			return;
		}
			
		// remove context from queue and process it
		requiredResourceForNextContext = checkResourcesAvailability(nextContext.getResources(), null, resourceNamesToUnLock);
		this.queuedContexts.remove(nextContext);
			
		// resourceNamesToUnlock contains the names of the previous resources.
		// requiredResourceForNextContext contains the resource objects which are required for the next context.
		// It is guaranteed that there is an overlap between the two - the resources which are to be reused.
		boolean needToWait = false;
		for (LockableResource requiredResource : requiredResourceForNextContext) {
			if (!resourceNamesToUnLock.contains(requiredResource.getName())) {
				if (requiredResource.isReserved() || requiredResource.isLocked()) {
					needToWait = true;
					break;
				}
			}
		}

		if (needToWait) {
			freeResources(resourceNamesToUnLock, build);
			save();
			return;
		} else {
			List<String> resourceNamesToLock = new ArrayList<String>();

			// lock all (old and new resources)
			for (LockableResource requiredResource : requiredResourceForNextContext) {
				try {
					requiredResource.setBuild(nextContext.getContext().get(Run.class));
					resourceNamesToLock.add(requiredResource.getName());
				} catch (Exception e) {
					// skip this context, as the build cannot be retrieved (maybe it was deleted while running?)
					LOGGER.log(Level.WARNING, "Skipping queued context for lock. Can not get the Run object from the context to proceed with lock, " +
							"this could be a legitimate status if the build waiting for the lock was deleted or" +
							" hard killed. More information at Level.FINE if debug is needed.");
					LOGGER.log(Level.FINE, "Can not get the Run object from the context to proceed with lock", e);
					unlockNames(resourceNamesToUnLock, build, inversePrecedence);
					return;
				}
			}

			// determine old resources no longer needed
			List<String> freeResources = new ArrayList<String>();
			for (String resourceNameToUnlock : resourceNamesToUnLock) {
				boolean resourceStillNeeded = false;
				for (LockableResource requiredResource : requiredResourceForNextContext) {
					if (resourceNameToUnlock == requiredResource.getName()) {
						resourceStillNeeded = true;
						break;
					}
				}

				if (!resourceStillNeeded) {
					freeResources.add(resourceNameToUnlock);
				}
			}

			// free old resources no longer needed
			freeResources(freeResources, build);

			// continue with next context
			LockStepExecution.proceed(resourceNamesToLock, nextContext.getContext(), nextContext.getResourceDescription(), inversePrecedence);
		}
		save();
	}

	/**
	 * Returns the next queued context with all its requirements satisfied.
	 *
	 * @param resourceNamesToUnLock resource names locked at the moment but available is required (as they are going to be unlocked soon
	 * @param inversePrecedence false pick up context as they are in the queue or true to take the most recent one (satisfying requirements)
	 * @return the context or null
	 */
	@CheckForNull
	private QueuedContextStruct getNextQueuedContext(List<String> resourceNamesToUnLock, boolean inversePrecedence) {
		QueuedContextStruct newestEntry = null;
		List<LockableResource> requiredResourceForNextContext = null;
		if (!inversePrecedence) {
			for (QueuedContextStruct entry : this.queuedContexts) {
				if (checkResourcesAvailability(entry.getResources(), null, resourceNamesToUnLock) != null) {
					return entry;
				}
			}
		} else {
			long newest = 0;
			List<QueuedContextStruct> orphan = new ArrayList<QueuedContextStruct>();
			for (QueuedContextStruct entry : this.queuedContexts) {
				if (checkResourcesAvailability(entry.getResources(), null, resourceNamesToUnLock) != null) {
					try {
						Run<?, ?> run = entry.getContext().get(Run.class);
						if (run.getStartTimeInMillis() > newest) {
							newest = run.getStartTimeInMillis();
							newestEntry = entry;
						}
					} catch (Exception e) {
						// skip this one, for some reason there is no Run object for this context
						orphan.add(entry);
					}
				}
			}
			if (!orphan.isEmpty()) {
				this.queuedContexts.removeAll(orphan);
			}
		}

		return newestEntry;
	}

	/**
	 * Creates the resource if it does not exist.
	 */
	public synchronized boolean createResource(String name) {
		
		LockableResource existent = fromName(name);
		if (existent == null) {
			getResources().add(new LockableResource(name));
			save();
			return true;
		}
		return false;
	}

	public synchronized boolean createResourceWithLabel(String name, String label) {
		LockableResource existent = fromName(name);
		if (existent == null) {
			getResources().add(new LockableResource(name, "", label, null));
			save();
			return true;
		}
		return false;
	}

	public synchronized boolean reserve(List<LockableResource> resources,
			String userName) {
		for (LockableResource r : resources) {
			if (r.isReserved() || r.isLocked() || r.isQueued()) {
				return false;
			}
		}
		for (LockableResource r : resources) {
			r.setReservedBy(userName);
		}
		save();
		return true;
	}

	public synchronized void unreserve(List<LockableResource> resources) {
		for (LockableResource r : resources) {
			r.unReserve();
		}
		save();
	}

	@Override
	public String getDisplayName() {
		return "External Resources";
	}

	public synchronized void reset(List<LockableResource> resources) {
		for (LockableResource r : resources) {
			r.reset();
		}
		save();
	}

	@Override
	public boolean configure(StaplerRequest req, JSONObject json)
			throws FormException {
		localResources.clear();

		try {
			List<LockableResource> newResouces = req.bindJSONToList(
					LockableResource.class, json.get("localResources"));

			for (LockableResource r : newResouces) {
				LockableResource old = fromName(r.getName());
				if (old != null) {
					r.setBuild(old.getBuild());
					r.setQueued(r.getQueueItemId(), r.getQueueItemProject());
				}
				if (fromName(r.getName(), getResources()) == null){
					// add any resources that is not part of the stratos resource list
					localResources.add(r);
				}
			}

			// Get the stratos config
			stratosURL = json.get("stratosURL").toString();
			username = json.getString("username").toString();
			password = Secret.fromString(json.getString("password").toString()).getEncryptedValue();
			
			save();
			return true;
		} catch (JSONException e) {
			return false;
		}
	}

	/**
	 * Checks if there are enough resources available to satisfy the requirements specified
	 * within requiredResources and returns the necessary available resources.
	 * If not enough resources are available, returns null.
	 */
	public synchronized List<LockableResource> checkResourcesAvailability(LockableResourcesStruct requiredResources,
			@Nullable PrintStream logger, @Nullable List<String> lockedResourcesAboutToBeUnlocked) {
		// get possible resources
		int requiredAmount = 0; // 0 means all
		List<LockableResource> candidates = new ArrayList<LockableResource>();
		if (requiredResources.label != null && requiredResources.label.isEmpty()) {
			candidates = requiredResources.required;
		} else {
			candidates = getResourcesWithLabel(requiredResources.label, null);
			if (requiredResources.requiredNumber != null) {
				try {
					requiredAmount = Integer.parseInt(requiredResources.requiredNumber);
				} catch (NumberFormatException e) {
					requiredAmount = 0;
				}
			}
		}

		if (requiredAmount == 0) {
			requiredAmount = candidates.size();
		}

		// start with an empty set of selected resources
		List<LockableResource> selected = new ArrayList<LockableResource>();

		// some resources might be already locked, but will be freeed.
		// Determine if these resources can be reused
		if (lockedResourcesAboutToBeUnlocked != null) {
			for (LockableResource candidate : candidates) {
				if (lockedResourcesAboutToBeUnlocked.contains(candidate.getName())) {
					selected.add(candidate);
				}
			}
			// if none of the currently locked resources can be reussed,
			// this context is not suitable to be continued with
			if (selected.size() == 0) {
				return null;
			}
		}

		for (LockableResource rs : candidates) {
			if (selected.size() >= requiredAmount) {
				break;
			}
			if (!rs.isReserved() && !rs.isLocked()) {
				selected.add(rs);
			}
		}

		if (selected.size() < requiredAmount) {
			if (logger != null) {
				logger.println("Found " + selected.size() + " available resource(s). Waiting for correct amount: " + requiredAmount + ".");
			}
			return null;
		}

		return selected;
	}

	/*
	 * Adds the given context and the required resources to the queue if
	 * this context is not yet queued.
	 */
	public void queueContext(StepContext context, LockableResourcesStruct requiredResources, String resourceDescription) {
		for (QueuedContextStruct entry : this.queuedContexts) {
			if (entry.getContext() == context) {
				return;
			}
		}

		this.queuedContexts.add(new QueuedContextStruct(context, requiredResources, resourceDescription));
		save();
	}

	public boolean unqueueContext(StepContext context) {
		for (Iterator<QueuedContextStruct> iter = this.queuedContexts.listIterator(); iter.hasNext(); ) {
			if (iter.next().getContext() == context) {
				iter.remove();
				save();
				return true;
			}
		}
		return false;
	}

	public static LockableResourcesManager get() {
		return (LockableResourcesManager) Jenkins.getInstance()
				.getDescriptorOrDie(LockableResourcesManager.class);
	}
	
	@Exported
	public List<LockableResource> getlocalResources() {
		return localResources;
	}
	
	@Exported
	public List<LockableResource> getOriginalResourcesFromConfigFile() {
		return resources;
	}
	
	public boolean compareLocalResources(List<String> resources){
		List<String> localResourcesString = new ArrayList<String>();
		for (LockableResource lr : localResources) {
			localResourcesString.add(lr.toString());
		}
		return localResourcesString.containsAll(resources);
	}
	
	public boolean compareRemoteResources(List<String> jobResources){
		List<String> remoteResourcesString = new ArrayList<String>();
		for (LockableResourceStratos rr : getStratosResources()) {
			remoteResourcesString.add(rr.toString());
		}
		return remoteResourcesString.containsAll(jobResources);
	}
	
	@Exported
	public List<LockableResourceStratos> getStratosResources() {
		return LockableResourceStratos.getStratosResources();
	}
	
	@DataBoundSetter
	public void setStratosURL(String stratosURL) {
		this.stratosURL = stratosURL;
	}
	
	@DataBoundSetter
	public void setUsername(String username) {
		this.username = username;
	}
	
	@DataBoundSetter
	public void setPassword(String password) {
		this.password = password;
	}
	
	@Exported
	public String getStratosURL() {
		return stratosURL;
	}
	
	@Exported
	public String getUsername() {
		return username;
	}
	
	@Exported
	public String getPassword() {
		return password;
	}	
	
	private static final Logger LOGGER = Logger.getLogger(LockableResourcesManager.class.getName());

}

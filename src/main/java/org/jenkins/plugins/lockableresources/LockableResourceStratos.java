package org.jenkins.plugins.lockableresources;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.queue.LockRunListener;

import com.seastreet.client.api.ClientFactory;
import com.seastreet.client.api.StratOSControllerAPI;
import com.seastreet.client.config.StratOSClientConfiguration;
import com.seastreet.client.exception.StratOSRESTClientConfigurationException;
import com.seastreet.stratos.dto.Objective;
import com.seastreet.stratos.dto.builders.Builders;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.model.User;
import hudson.util.Secret;

public class LockableResourceStratos extends LockableResource {

	public LockableResourceStratos(String name) {
		super(name);
	}
	
	/**
	 * Creates a stratos resource if one does not exist
	 * @param userName
	 */
	@Override
	public void setReservedBy(String userName) {
		LOGGER.log(Level.FINE, "Setting reserved by to " + userName);		
		super.setReservedBy(userName);
		// only create the reservation if one is not there
		if(!this.isReserved())
			createStratosReservation(this,userName,false);
	}

	/**
	 * Removes the stratos reservation objective
	 */
	@Override
	public void unReserve() {
		super.unReserve();
		// delete the reservation objective
		if(isStratosResourceReserved(getSelfLink()))
			deleteStratosReservation(this);
	}

	/**
	 * Creates a stratos resource if lockedBy is not null
	 * Will remove the stratos resource if lockedBy is null
	 * @param lockedBy
	 */
	@Override
	public void setBuild(Run<?, ?> lockedBy) {
		super.setBuild(lockedBy);
		// If the lockedBy has a value then we want to create the reservation objective
		// If the lockedBy is null then we are resetting the resource and want to remove the reservation objective
		if(lockedBy != null){
			LOGGER.log(Level.FINE, "Locking reservation under: " + lockedBy);
			createStratosReservation(this);
		}else{
			LOGGER.log(Level.FINE, "Removing reservation");
			deleteStratosReservation(this);
		}		
	}

	/**
	 * Creates a stratos reservation objective on a given resource
	 * If the job was started by Jenkins it will use Jenkins as the username
	 * @param resource
	 */
	private void createStratosReservation(LockableResource resource){
		// Get the user who locked this resource
		String user = LockRunListener.getUserId();
		// If there are no users then Jenkins kicked off the build
		if(user ==  null)
			user = "Jenkins";
		LOGGER.log(Level.FINE, "Creating reservation on:  " + resource + " for user: " + user);
		createStratosReservation(resource, user, true);	
	}
	/**
	 * Creates a stratos reservation objective owned by the user who locked the resource
	 * @param resource
	 * @param username
	 */
	private void createStratosReservation(LockableResource resource, String username, boolean reserveIndefinitely){
		
		StratOSControllerAPI stratosAPI = getControllerAPI();
		Map<String,Object> props = new HashMap<>();
		String userId = User.get(username).getId();
		LOGGER.log(Level.FINE,"The userid for " + username +" is " + userId);
		props.put("owner", userId);
		if(reserveIndefinitely){
			LOGGER.log(Level.FINE, "Locking " + resource + " indefinitely");
			props.put("end", "December 31, 9999 6:00:00 PM EDT");
		}
		if(resource.getBuildName() !=  null)
			props.put("buildExternalizableId", resource.getBuildName().replaceAll("\\s", ""));
		List<String> governors = Arrays.asList(resource.getSelfLink());
		LOGGER.log(Level.FINE, "Createing Reservation on " + resource.getName());
		Objective createReservationRep =  Builders.objective().name("JenkinsReservationOn_"+resource.getName())
				.description(username + " reserved this resource from Jenkins")
				.type("SUDS/1.0/reservation")
				.governors(governors).properties(props)
				.build();
		stratosAPI.objectives().create(createReservationRep);
	}
	
	/**
	 * Removes the stratos reservation objective on a given resource
	 * @param resource
	 */
	private void deleteStratosReservation(LockableResource resource){
	
		StratOSControllerAPI stratosAPI = getControllerAPI();
		Optional<Objective> lab = stratosAPI.objectives().get(resource.getSelfLink());
		// get the reservation objective's URL from the property that is set on the lab
		Object reservationUrl = lab.get().getProperty("currentReservationUrl");
		LOGGER.log(Level.FINE, "currentReservationUrl: " + reservationUrl);
		// The destroy method needs only the uid
		String reservationUid = getUidFromSelfLink(reservationUrl.toString());
		LOGGER.log(Level.FINE, "Deleteing reservation "+reservationUid+" on " + resource );
		stratosAPI.objectives().destroy(reservationUid);
	}

	/**
	 * Removes the URL from a self link to return only the uid
	 * @param selfLink
	 * @return uid
	 */
	private String getUidFromSelfLink(String selfLink) {
		String[] parts = selfLink.split("/");
		return parts[parts.length - 1];
	}
	
	/**
	 * Test to see if stratos is healthy
	 * @param stratosAPI
	 * @return boolean of stratos health
	 */
	public static boolean isStratosHealthy(StratOSControllerAPI stratosAPI){
		
		try{
			stratosAPI.server().getClusterHealth();
			LOGGER.log(Level.FINE, stratosAPI.getUrl() + " is healthy");
			return true;
		}catch (Exception e){
			LOGGER.log(Level.FINE, "Error occered checking: " + stratosAPI.getUrl() + " error: " + e);
			return false;
		}
		
	}
	
	/**
	 * Checks to see if there is a reservations objective
	 * @return true if reservation objective is there
	 */
	@Override
	public boolean isReserved() {
		LOGGER.log(Level.FINE, "Checking stratos reservations");
		return isStratosResourceReserved(getSelfLink());
	}

	/**
	 * Checks to see if the property currentReservationUrl is set
	 * @param selfLink
	 * @return true if currentReservationUrl is not null
	 */
	private boolean isStratosResourceReserved(String selfLink){
		
		StratOSControllerAPI stratosAPI = getControllerAPI();
		Optional<Objective> lab = stratosAPI.objectives().get(selfLink);
		// Check to see if the property on the lab is set for the reservation
		Object reservationUrl = lab.get().getProperty("currentReservationUrl");
		LOGGER.log(Level.FINE, "currentReservationUrl: " + reservationUrl);
		if(reservationUrl == null){
			return false;
		}
		return true;
	}
	
	/**
	 * Gets all the stratos resources
	 * @return list of stratos resources
	 */
	public static List<LockableResourceStratos> getStratosResources() {
		
	 List<LockableResourceStratos> stratosResources = new ArrayList<LockableResourceStratos>();
	 StratOSControllerAPI controllerAPI = getControllerAPI();

		try {
			// Get all of the lab objectives
			List<Objective> labs = controllerAPI.objectives().getByType("SUDS/1.0/lab");
			for (Objective lab : labs) {

				LockableResourceStratos stratosResource = new LockableResourceStratos(lab.getName());

				LOGGER.log(Level.FINE, "objectiveName: " + lab.getName());
				if (fromSelfLink(lab.getSelf(), stratosResources) == null){

					stratosResource.setSelfLink(lab.getSelf());
					stratosResource.setDescription(lab.getDescription());
					stratosResource.setLabels(String.join(",", lab.getTags()));
					stratosResources.add(stratosResource);
					
					Object currentReservationUrl = lab.getProperty("currentReservationUrl");
					if(currentReservationUrl != null){
						Optional<Objective> reservation = controllerAPI.objectives().get(currentReservationUrl.toString());
						// Set buildExternalizableId to the build name and number so that the resource will unlock when the build is done.
						Object buildId = reservation.get().getProperty("buildExternalizableId");
						if(buildId != null){
						LOGGER.log(Level.FINE, "Setting buildExternalizableId to " + buildId.toString() + " on lab resource " + stratosResource.getName());
						stratosResource.setBuildExternalizableId(buildId.toString());
						}
						// Set the reserve by
						Object user = reservation.get().getProperty("owner");
						if(user != null){
							LOGGER.log(Level.FINE, "Setting the user to " + user.toString() + " on lab resource " + stratosResource.getName());
							stratosResource.setReservedBy(user.toString());
						}
						
					}
				}
			}

			LOGGER.log(Level.FINE, "stratosResources: " + stratosResources);
			return stratosResources;

		} catch(Exception e){
			LOGGER.log(Level.SEVERE, "Failed to get lab resources. Error: " + e);
			return null;
		}
		
	}

	/**
	 * Checks to see if the resource's self link is in the resource list
	 * @param resourceSelfLink
	 * @param resourceList
	 * @return LockableResource or null if not there
	 */
	public static LockableResource fromSelfLink(String resourceSelfLink, List<LockableResourceStratos> resourceList) {
		if (resourceSelfLink != null) {
			for (LockableResource r : resourceList) {
				if (resourceSelfLink.equals(r.getSelfLink()))
					return r;
			}
		}
		return null;
	}
	
	public static StratOSControllerAPI getControllerAPI(){
		
		StratOSClientConfiguration config;
		
		String stratosUrl = LockableResourcesManager.get().getStratosURL();
		String username = LockableResourcesManager.get().getUsername();
		String password = Secret.toString(Secret.decrypt(LockableResourcesManager.get().getPassword()));
		StratOSControllerAPI controllerAPI = null;
		URL url = null;
		String ssProtocol = "";
	
		if (stratosUrl != null && username != null && password != null){																													
			try {
				url = new URL(stratosUrl);
				// Set the ssProtocol to SSL when we are using SSL
				if(url.getProtocol().equals("https"))
					ssProtocol = "SSL";
				
				config = com.seastreet.client.config.Builders.config().url(url).username(username).password(password).ssProtocol(ssProtocol).build();
				try {
					controllerAPI = ClientFactory.getControllerAPI(config);
				} catch (StratOSRESTClientConfigurationException e) {
					LOGGER.log(Level.FINEST, "Caught Stratos REST client configuration exception: " + e);
				}
		
			} catch (MalformedURLException e) {
				LOGGER.log(Level.FINEST, "Caught Malformed URL exception: " + e);
			}
		}
		
		return controllerAPI;
	}
	
	
	@Extension
	public static class DescriptorImpl extends Descriptor<LockableResource> {

		@Override
		public String getDisplayName() {
			return "StratosResource";
		}

	}

	private static final Logger LOGGER = Logger.getLogger(LockableResourceStratos.class.getName());
}

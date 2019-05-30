package org.jenkins.plugins.lockableresources;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.queue.LockRunListener;

import com.seastreet.client.api.StratOSControllerAPI;
import com.seastreet.stratos.dto.Objective;
import com.seastreet.stratos.dto.builders.Builders;

import hudson.model.User;

public class LockableResourceStratos extends LockableResource {

	public LockableResourceStratos(String name) {
		super(name);
		// TODO Auto-generated constructor stub
	}
	
	public static void createStratosReservation(LockableResource resource){
		
		StratOSControllerAPI stratosAPI = LockableResourcesManager.get().getControllerAPI();
		String user = LockRunListener.getUserId();
		if(user ==  null)
			user = "Jenkins";
		LOGGER.log(Level.FINE, "The owner of the reservation will be: " + user + " for " + resource.getName());
		Map<String,Object> props = new HashMap<>();
		props.put("owner", user);
		props.put("buildExternalizableId", resource.getBuildName().replaceAll("\\s", ""));
		List<String> governors = Arrays.asList(resource.getSelfLink());
		LOGGER.log(Level.FINE, "Createing Reservation on " + resource.getName());
		Objective createReservationRep =  Builders.objective().name("JenkinsReservationOn_"+resource.getName())
				.description("Reservation was created on Jenkins by user " + user +  " for build " + resource.getBuildName())
				.type("SUDS/1.0/reservation")
				.governors(governors).properties(props)
				.build();
		stratosAPI.objectives().create(createReservationRep);	
		
	}
	
	
	public static void createStratosReservation(LockableResource resource, String username){
		
		StratOSControllerAPI stratosAPI = LockableResourcesManager.get().getControllerAPI();
		Map<String,Object> props = new HashMap<>();
		String userId = User.get(username).getId();
		LOGGER.log(Level.FINE,"The userid for " + username +" is " + userId);
		props.put("owner", userId);
		List<String> governors = Arrays.asList(resource.getSelfLink());
		LOGGER.log(Level.FINE, "Createing Reservation on " + resource.getName());
		Objective createReservationRep =  Builders.objective().name("JenkinsReservationOn_"+resource.getName())
				.description(username + " reserved this resource from Jenkins")
				.type("SUDS/1.0/reservation")
				.governors(governors).properties(props)
				.build();
		stratosAPI.objectives().create(createReservationRep);
	}
	
	public static void deleteStratosReservation(LockableResource resource){
	
		StratOSControllerAPI stratosAPI = LockableResourcesManager.get().getControllerAPI();
		String labSelfLink = resource.getSelfLink();
		List<Objective> reservationObjectives = stratosAPI.objectives().getByType("SUDS/1.0/reservation");
		for(Objective currentReservation : reservationObjectives){
			String reservationUID = getUidFromSelfLink(currentReservation.getSelf());
			List<String> governors = currentReservation.getGovernors();
			for (String currentGov : governors){
				if(currentGov.equals(labSelfLink)){
					LOGGER.log(Level.FINE, "Deleting reservation: " + reservationUID);
					stratosAPI.objectives().destroy(reservationUID);
				}
			}
		}
	}

	public static String getUidFromSelfLink(String selfLink) {
		String[] parts = selfLink.split("/");
		return parts[parts.length - 1];
	}
	
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
	
	public static boolean isStratosResourceReserved(String selfLink){
		StratOSControllerAPI stratosAPI = LockableResourcesManager.get().getControllerAPI();
		try{
			Optional<Objective> govRep = stratosAPI.objectives().get(selfLink);
			stratosAPI.objectives().getFirstConstituentOfType(govRep.get(), "SUDS/1.0/reservation");
			return true;
			}catch(Exception e){
				LOGGER.severe("Error getting reservation: " + e);
				return false;
			}
	}

	private static final Logger LOGGER = Logger.getLogger(LockableResourceStratos.class.getName());
}

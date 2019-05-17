package org.jenkins.plugins.lockableresources;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.seastreet.client.api.StratOSControllerAPI;
import com.seastreet.stratos.dto.Objective;
import com.seastreet.stratos.dto.builders.Builders;

public class LockableResourceStratos extends LockableResource {

	public LockableResourceStratos(String name) {
		super(name);
		// TODO Auto-generated constructor stub
	}

	
	public static void createStratosReservation(LockableResource resource){
		
		StratOSControllerAPI stratosAPI = LockableResourcesManager.get().getControllerAPI();
		Map<String,Object> props = new HashMap<>();
		props.put("owner", "Jenkins");
		props.put("buildExternalizableId", resource.getBuildName().replaceAll("\\s", ""));
		List<String> governors = Arrays.asList(resource.getSelfLink());
		LOGGER.log(Level.FINE, "Createing Reservation on " + resource.getName());
		Objective createReservationRep =  Builders.objective().name("JenkinsRservationOn_"+resource.getName())
				.description("Jenkins reserved this Lab for: " + resource.getBuildName())
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

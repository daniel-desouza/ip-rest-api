package api;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.net.util.SubnetUtils.SubnetInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.google.gson.Gson;

/**
 * IPManagementController provides endpoints for managing IP Addresses.
 * 
 * /list
 * 		Reads JSON file and returns all of the created IP addresses and their statuses.
 * 
 * /create?address=xxx.xxx.xxx.xxx/yy
 * 		Takes in a CIDR block and adds the IP addresses in that range to a JSON file.
 * 
 * /acquire?address=xxx.xxx.xxx.xxx
 * 		Sets status of an ip address to acquired.
 * 
 * /release?address=xxx.xxx.xxx.xxx
 * 		Sets status of an ip address to available.
 * 
 * @author Daniel de Souza
 *
 */
@RestController
public class IPManagementController {

	final String FILE_NAME = "JSON-DataStore.json";
	final String DATA_STORE_DIR_NAME = "datastore";
	final String WORKING_DIR_NAME = Paths.get(".").toAbsolutePath().normalize().toString();
	final Path DATA_STORE_PATH = Paths.get(WORKING_DIR_NAME + File.separator + DATA_STORE_DIR_NAME + File.separator + FILE_NAME);
	final String AVAILABLE_STATUS = "available";
	final String ACQUIRED_STATUS = "acquired";
	final int MINIMUM_MASK_BITS = 24;

	@RequestMapping("/")
	public String home() {
		return "I assumed IPv4 addresses only.<br />I also limited CIDR block mask to between 24 and 32 bits for proof of concept."
				+ "<br />Having used a text file for the data store instead of a database introduced performance issues. "
				+ " The text file will be written to the workspace/directory in which you run this application. Calls to the create endpoint will overwrite the file, it will not append new addresses to the file."
				+ "<br /><br />Available Endpoints:"
				+ "<br />/list"
				+ "<br />/create?address=xxx.xxx.xxx.xxx/yy"
				+ "<br />/acquire?address=xxx.xxx.xxx.xxx"
				+ "<br />/release?address=xxx.xxx.xxx.xxx";
	}
    
	/**
	 * Lists all of the IP addresses that have been created from the create endpoint. Reads from the data store file.
	 * 
	 * @return String a JSON representation of all of the IP addresses in the data store.
	 */
	@RequestMapping("/list")
    public String listIpAddresses() {
		if (dataStoreExists(DATA_STORE_PATH)) {
    		String readFromFile = null;
    		try {
    			readFromFile = Files.readAllLines(DATA_STORE_PATH).get(0);
    		} catch (IOException e) {
    			e.printStackTrace();
    			return e.getMessage();
    		}
    		return readFromFile;
    	} else {
    		return "No IP Addresses have been created yet.";
    	}
    }
    
	/**
	 * Creates a JSON file from a CIDR block notation input. Overwrites the JSON file with each call.
	 * 
	 * @param address the CIDR block notation input
	 * @return String a JSON representation of the addresses created from the CIDR block provided
	 */
    @RequestMapping("/create")
    public String createIpAddresses(@RequestParam(value="address", defaultValue="") String address) {
    	
    	SubnetUtils subnetUtil;
    	try {
    		// SubnetUtils will try to validate and attempt to parse the input parameter address.
    		subnetUtil = new SubnetUtils(address);
    	} catch (IllegalArgumentException e) {
    		e.printStackTrace();
    		return e.getMessage();
    	}
    	
    	if (!isValidMaskBits(address)) {
    		return String.format("Value [%s] not in range [%d,32]", StringUtils.substringAfterLast(address, "/"), MINIMUM_MASK_BITS);
    	}
    	
    	// Include network and broadcast addresses in the result.
    	subnetUtil.setInclusiveHostCount(true);
    	SubnetInfo subnetInfo = subnetUtil.getInfo();
    	
    	JSONArray ja = new JSONArray();
    	
    	// For each ip address in the range, add a JSON object with the address and status of available to a JSON array.
    	Arrays.stream(subnetInfo.getAllAddresses()).forEach(add -> {
    		try {
				ja.put(new JSONObject().put("address", add).put("status", AVAILABLE_STATUS));
			} catch (JSONException e) {
				e.printStackTrace();
			}
    	});
    	
		byte[] strToBytes = ja.toString().getBytes();
    	
    	if (dataStoreExists(DATA_STORE_PATH)) {
    		try {
    			// Write to the data store.
    			Files.write(DATA_STORE_PATH, strToBytes);
    		} catch (IOException e) {
    			e.printStackTrace();
    		}
    	} else {
    		try {
    			// Create data store and then write to it.
				Files.createDirectories(Paths.get(WORKING_DIR_NAME + File.separator + DATA_STORE_DIR_NAME));
    			Files.write(DATA_STORE_PATH, strToBytes);
			} catch (IOException e) {
				e.printStackTrace();
			}
    	}

    	// Return JSON containing all created IP addresses and their statuses
    	return ja.toString();
    }

	/**
     * Given an IP address that exists in the data store and has a status of available, set the status of that IP address to acquired.
     * 
     * @param address the IP address to acquire
     * @return String a message indicating whether the address was successfully acquired
     */
    @RequestMapping("/acquire")
    public String acquireIpAddress(@RequestParam(value="address") String address) {
    	
    	if (dataStoreExists(DATA_STORE_PATH)) {

    		ArrayList<IPAddress> addressListFromFile = getAddressesFromDataStore();

    		/* 
    		 * This is a point of inefficiency that I would prefer to refactor into a map given the text file-based data source. 
    		 * Iterating through the ArrayList multiple times through Java Streams is not ideal. I'm calling .stream() 4 times in the next few lines which is not efficient over large data sets.
    		 * This problem would not arise had I used a database for the data store.
    		 */
    		boolean addressExists = addressExists(addressListFromFile, address);

    		// Check to see if the IP address provided exists.
    		if (addressExists) {
    			
    			// Check to see if the IP address provided is acquirable.
        		boolean addressAcquirable = addressAcquirable(addressListFromFile, address);

        		if (addressAcquirable) {
            		
        			// Acquire the IP address - Stream the address list to a new list with the transformation of the address' status to acquired.
        			List<IPAddress> newAddresses = addressListFromFile.stream()
        					.map(o -> o.getAddress().equals(address) && AVAILABLE_STATUS.equals(o.getStatus()) ? new IPAddress(address, ACQUIRED_STATUS) : o)
        					.collect(Collectors.toList());
            		
        			JSONArray newJArray = new JSONArray();
            		
        			// Add the IPAddress objects to the JSONArray.
            		newAddresses.stream().forEach(add -> {
                		try {
            				newJArray.put(new JSONObject().put("address", add.getAddress()).put("status", add.getStatus()));
            			} catch (JSONException e) {
            				e.printStackTrace();
            			}
                	});
                	
            		// Print the resulting JSON of IP addresses to the data store.
            		byte[] strToBytes = newJArray.toString().getBytes();
            		try {
            			Files.write(DATA_STORE_PATH, strToBytes);
            		} catch (IOException e) {
            			e.printStackTrace();
            			return e.getMessage();
            		}
            		
            		return String.format("Address [%s] successfully acquired.", address);
            		
        		} else {
        			// IP address provided is not available.
        			return String.format("Address [%s] cannot be acquired because it is already in use. It must be released before acquiring.", address);
        		}
    		} else {
    			// IP address provided does not exist.
    			return String.format("Address [%s] does not exist.", address);
    		}
    	} else {
    		// A data store doesn't exist
    		return "No IP Addresses have been created yet.";
    	}
    }

    /**
     * Given an IP address that exists in the data store and has a status of acquired, set the status of that IP address to available.
     * 
     * @param address the IP address to release
     * @return String a message indicating whether the address was successfully released
     */
	@RequestMapping("/release")
    public String releaseIpAddress(@RequestParam(value="address") String address) {
    	
    	if (dataStoreExists(DATA_STORE_PATH)) {

    		ArrayList<IPAddress> addressListFromFile = getAddressesFromDataStore();

    		/* 
    		 * This is a point of inefficiency that I would prefer to refactor into a map given the text file-based data source. 
    		 * Iterating through the ArrayList multiple times through Java Streams is not ideal. I'm calling .stream() 4 times in the next few lines which is not efficient over large data sets.
    		 * This problem would not arise had I used a database for the data store.
    		 */
    		boolean addressExists = addressExists(addressListFromFile, address);

    		// Check to see if the IP address provided exists.
    		if (addressExists) {
    			
    			// Check to see if the IP address provided is releasable.
        		boolean addressReleasable = addressReleasable(addressListFromFile, address);

        		if (addressReleasable) {
            		
        			// Release the IP address - Stream the address list to a new list with the transformation of the address' status to available.
        			List<IPAddress> newAddresses = addressListFromFile.stream()
        					.map(o -> o.getAddress().equals(address) && ACQUIRED_STATUS.equals(o.getStatus()) ? new IPAddress(address, AVAILABLE_STATUS) : o)
        					.collect(Collectors.toList());
            		
        			JSONArray newJArray = new JSONArray();
            		
        			// Add the IPAddress objects to the JSONArray.
            		newAddresses.stream().forEach(add -> {
                		try {
            				newJArray.put(new JSONObject().put("address", add.getAddress()).put("status", add.getStatus()));
            			} catch (JSONException e) {
            				e.printStackTrace();
            			}
                	});
                	
            		// Print the resulting JSON of IP addresses to the data store.
            		byte[] strToBytes = newJArray.toString().getBytes();
            		try {
            			Files.write(DATA_STORE_PATH, strToBytes);
            		} catch (IOException e) {
            			e.printStackTrace();
            			return e.getMessage();
            		}
            		
            		return String.format("Address [%s] successfully released.", address);
            		
        		} else {
        			// IP address provided is not releasable.
        			return String.format("Address [%s] cannot be released because it is already available. It must be acquired before releasing.", address);
        		}
    		} else {
    			// IP address provided does not exist.
    			return String.format("Address [%s] does not exist.", address);
    		}
    	} else {
    		// A data store doesn't exist
    		return "No IP Addresses have been created yet.";
    	}
    }

	/**
	 * Accesses the data store and creates a list of IPAddress objects based on the serialized JSON.
	 * 
	 * @return ArrayList<IPAddress> a list of the IPAddress objects found in the data store
	 */
	private ArrayList<IPAddress> getAddressesFromDataStore() {
   		String readFromFile = null;
		try {
			// Read JSON String from file. Validation should be performed here to prevent JSON Injection. Should use a library like json-sanitizer.
			readFromFile = Files.readAllLines(DATA_STORE_PATH).get(0);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		Gson g = new Gson();
		JSONArray ja = new JSONArray(readFromFile);
		ArrayList<IPAddress> addressListFromFile = new ArrayList<>();
		
		//For each object in the JSONArray, deserialize into an IPAddress object and add to ArrayList to maintain order.
		Iterator<Object> jaIterator = ja.iterator();
		while (jaIterator.hasNext()) {
			addressListFromFile.add(g.fromJson(jaIterator.next().toString(), IPAddress.class));
		}
		
		return addressListFromFile;
	}

	/**
	 * Checks if the IP address exists in the list of IPAddress objects.
	 * 
	 * @param addressListFromFile the list of IPAddress objects
	 * @param address the IP address to check
	 * @return boolean whether the IP address exists in the list
	 */
    private boolean addressExists(ArrayList<IPAddress> addressListFromFile, String address) {
    	return addressListFromFile.stream().anyMatch(add -> add.getAddress().equals(address));
	}

	/**
	 * Checks if the IP address is able to be acquired from the list of IPAddress objects.
	 * 
	 * @param addressListFromFile the list of IPAddress objects
	 * @param address the IP address to check
	 * @return boolean whether the IP address has status of available
	 */
    private boolean addressAcquirable(ArrayList<IPAddress> addressListFromFile, String address) {
    	return addressListFromFile.stream().anyMatch(add -> add.getAddress().equals(address) && AVAILABLE_STATUS.equals(add.getStatus()));
    }
    
	/**
	 * Checks if the IP address is able to be released from the list of IPAddress objects.
	 * 
	 * @param addressListFromFile the list of IPAddress objects
	 * @param address the IP address to check
	 * @return boolean whether the IP address has status of acquired
	 */
    private boolean addressReleasable(ArrayList<IPAddress> addressListFromFile, String address) {
    	return addressListFromFile.stream().anyMatch(add -> add.getAddress().equals(address) && ACQUIRED_STATUS.equals(add.getStatus()));
	}

    /**
     * Checks whether the data store exists.
     * 
     * @param path the file location to check
     * @return boolean whether the file was found in the location to check
     */
	private boolean dataStoreExists(Path path) {
    	return path.toFile().exists();
    }
	
	/**
	 * Checks the CIDR block mask bits against the custom number of minimum bits allowed.
	 * 
	 * @param address the CIDR block to check
	 * @return boolean whether the CIDR block bits are within the range we specify
	 */
    private boolean isValidMaskBits(String address) {
    	int maskBits = Integer.parseInt(StringUtils.substringAfterLast(address, "/"));
    	return maskBits >= MINIMUM_MASK_BITS;
	}


}

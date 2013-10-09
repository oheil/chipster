package fi.csc.microarray.client.visualisation.methods.gbrowser.fileIndex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.json.parsers.JSONParser;
import com.json.parsers.JsonParserFactory;

import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.DataUrl;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataRequest;
import fi.csc.microarray.client.visualisation.methods.gbrowser.util.GBrowserException;

@SuppressWarnings("rawtypes")
public class EnsemblRestApiClient {
	
	private static final int RETRY_LIMIT = 5;
	//FIXME concurrent access from multiple threads
	private static int wait = 1; //ms
	private static long timeOfLastRequest = -1;

	public static List<Map<String, String>> getAnnotations(DataUrl server, String species, DataRequest request) throws GBrowserException, IOException {
		
		Map jsonData = query(server.getUrl() + "/feature/region/" + species + "/",  request, "?feature=gene;feature=transcript;feature=cds;feature=exon");
		
		List<Map<String, String>> jsonMaps = new LinkedList<>();
		
		if (jsonData.size() == 0) {
			return jsonMaps;
		}
		
	    if (jsonData.size() > 1) {
	    	throw new GBrowserException("there is more than one root element: " + jsonData);
	    }
		
	    Object listObj = jsonData.values().iterator().next();
	    List valueList = (List) listObj;

	    
	    for (Object mapObj : valueList) {
	    	if (mapObj instanceof Map) {
	    		@SuppressWarnings("unchecked")
				Map<String, String> map = (Map<String, String>) mapObj;
	    		jsonMaps.add(map);
	    	}
	    }
	    
	    return jsonMaps;
	}
	
	@SuppressWarnings("unchecked")
	public static Map<String, String> getReferenceSequence(DataUrl server, String species,
			DataRequest request) throws GBrowserException, IOException {
		
		Map jsonData = query(server.getUrl() + "/sequence/region/" + species + "/",  request, "?content-type=application/json");
		
		return (Map<String, String>)jsonData;
	}
	
	public static Map query(String endpoint, DataRequest request, String query) throws GBrowserException, IOException {

		long startLong = Math.max(0, request.start.bp);
	    long endLong = Math.max(0, request.end.bp);
	    
	    if (startLong == endLong) {
	    	//length of the request range is zero
	    	return new HashMap<>();
	    }
	    
	    checkRequestSpeedLimit();
	    
	    String requestChr = "" + request.start.chr;
	    String requestStart = "" + startLong;
	    String requestEnd = "" + endLong;
	    
	    String urlString = endpoint + requestChr + ":" + requestStart + "-" + requestEnd + query;
	    URL url = new URL(urlString);
	 
	    URLConnection connection = url.openConnection();
	    HttpURLConnection httpConnection = (HttpURLConnection)connection;
	    connection.setRequestProperty("Content-Type", "application/json");
	 
	    InputStream response = null;
	    
	    for (int i = 0; i < RETRY_LIMIT; i++) {
	    	try {
	    		response = connection.getInputStream();
	    		break;
	    	} catch (IOException e) {
	    		if (httpConnection.getResponseCode() == 429) {
	    			//too many requests
	    			
	    		    String retryAfterString = httpConnection.getHeaderField("Retry-After");
	    		    int retryAfter = (int)Float.parseFloat(retryAfterString);
	    		    
	    		    //There are multiple threads making requests. Wait longer if the consequtive requests fail.
	    		    retryAfter = retryAfter * (1 + i);
	    			System.out.println("Too many requests for the server: " + httpConnection.getResponseMessage() + " Retry attempt " + i + " after " + retryAfter + " seconds");
	    			
	    			try {
						Thread.sleep(retryAfter);
					} catch (InterruptedException e1) {
					}
	    		}
	    	}
	    }

	    int responseCode = httpConnection.getResponseCode();
	 
	    if(responseCode != 200) {
	    	
	    	throw new GBrowserException( 
	    			"Response code " + responseCode + ": " + httpConnection.getResponseMessage() + " " + url + 
	    			"\nPage content: " + inputStreamToString(httpConnection.getErrorStream()));
	    }
	    
	    String output = inputStreamToString(response);
	    
	    JsonParserFactory factory = JsonParserFactory.getInstance();
	    JSONParser parser=factory.newJsonParser();

	    if ("[]".equals(output)) {
	    	//Json library doesn't like these empty documents, handle it separately
	    	return new HashMap<>();
	    }
	    
	    Map jsonData=parser.parseJson(output);
	   	    
	    updateWaitTime(httpConnection);
	   
	    return jsonData;
	}

	private static String inputStreamToString(InputStream stream)
			throws UnsupportedEncodingException, IOException {
		String output;
		Reader reader = null;
	    try {
	    	InputStreamReader inputStreamReader = new InputStreamReader(stream, "UTF-8");	    	
	    	reader = new BufferedReader(inputStreamReader);
	    	StringBuilder builder = new StringBuilder();
	    	char[] buffer = new char[8192];
	    	int read;
	    	while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
	    		builder.append(buffer, 0, read);
	    	}
	    	output = builder.toString();
	    } 
	    finally {
	    	if (reader != null) try {
	    		reader.close(); 
	    	} catch (IOException logOrIgnore) {
	    		logOrIgnore.printStackTrace();
	    	}
	    }
		return output;
	}

	
	private static void checkRequestSpeedLimit() {
	    if (timeOfLastRequest != -1) {
	    	long now = System.currentTimeMillis();
	    	long earliestTime = timeOfLastRequest + (wait - 1); //1 is the minimum value for the wait
	    	
	    	long waitTime = earliestTime - now;
	    	if (waitTime > 0) {
	    		System.out.println("Rest api request speed limit exceeded, waiting " + waitTime + " before next request.");
	    		try {
					Thread.sleep(waitTime);
				} catch (InterruptedException e) {
					//In a worst case we just use our request quota of this hour too early and have to wait to end of the hour until the
					//quota is reset
				}
	    	}
	    }
	}

	private static void updateWaitTime(HttpURLConnection httpConnection) {
	    String remainingString = httpConnection.getHeaderField("X-RateLimit-Remaining");
	    String limitString = httpConnection.getHeaderField("X-RateLimit-Limit");
	    String resetString = httpConnection.getHeaderField("X-RateLimit-Reset");
	    
	    int remainingRequestCount = Integer.parseInt(remainingString);
	    int requestCountLimit = Integer.parseInt(limitString);
	    int secondsUntilReset = Integer.parseInt(resetString);
	    	    
	    int resetInterval = 60*60;
	    
	    double remainingTimeRatio = secondsUntilReset / (double) resetInterval;
	    double remainingRequestRatio = remainingRequestCount / (double) requestCountLimit;
	   
//	    System.out.println("Reset: " + resetRatio + "\tRequest: " + requestRatio + "\tWait was: " + wait);
	    if (remainingTimeRatio > remainingRequestRatio) {
	    	wait = wait * 2;
	    } else { 
	    	wait = Math.max(1, wait / 2);
	    }
	    
	    timeOfLastRequest = System.currentTimeMillis();
	}
}

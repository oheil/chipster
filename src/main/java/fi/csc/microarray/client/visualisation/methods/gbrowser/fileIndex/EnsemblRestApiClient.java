package fi.csc.microarray.client.visualisation.methods.gbrowser.fileIndex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.sql.PseudoColumnUsage;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.json.parsers.JSONParser;
import com.json.parsers.JsonParserFactory;

import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.DataUrl;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Chromosome;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataRequest;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Region;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Strand;
import fi.csc.microarray.client.visualisation.methods.gbrowser.util.GBrowserException;

@SuppressWarnings("rawtypes")
public class EnsemblRestApiClient {
	
	private static final int RETRY_LIMIT = 5;
	//FIXME concurrent access from multiple threads
	private static int MIN_WAIT = 1000/6; //at the moment the burst limit is 6 requests per second 
	private static int wait = MIN_WAIT; //ms
	private static long timeOfLastRequest = -1;

	public static List<Map<String, String>> getAnnotations(DataUrl server, String species, DataRequest request) throws GBrowserException, IOException {
		
		Map jsonData = regionQuery(server.getUrl() + "/feature/region/" + species + "/",  request, "?feature=gene;feature=transcript;feature=cds;feature=exon");
		return getTypedMaps(jsonData);
	}
	

	public static List<Map<String, String>> getRepeats(DataUrl server, String species, DataRequest request) throws GBrowserException, IOException {
		
		Map jsonData = regionQuery(server.getUrl() + "/feature/region/" + species + "/", request,  "?feature=repeat;content-type=application/json");		
		return getTypedMaps(jsonData);
	}

	private static List<Map<String, String>> getTypedMaps(Map jsonData)
			throws GBrowserException {
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
	
	public static Map<String, String> getReferenceSequence(DataUrl server, String species,
			DataRequest request) throws GBrowserException, IOException {
		
		Map jsonData = regionQuery(server.getUrl() + "/sequence/region/" + species + "/",  request, "?content-type=application/json");
		
		return getTypedMap(jsonData);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> getTypedMap(Map jsonData) {
		return (Map<String, String>)jsonData;
	}

	public static Region getLocationOfGene(DataUrl server, String species,
			String geneName) throws GBrowserException, IOException {
		
		//http://beta.rest.ensembl.org/xrefs/symbol/homo_sapiens/BRCA2?content-type=application/json
		//[{"type":"gene","id":"ENSG00000139618"},{"type":"gene","id":"LRG_293"},{"type":"transcript","id":"ENST00000544455"},{"type":"transcript","id":"LRG_293t1"}]
		
		Map jsonData = query(server.getUrl() + "/xrefs/symbol/" + species + "/" + geneName + "?content-type=application/json");
		
		List<Map<String, String>> geneIdMaps = getTypedMaps(jsonData);
		
		String id = null;
		
		for (Map<String, String> map : geneIdMaps) {
			if ("gene".equals(map.get("type"))) {
				id = map.get("id");
				//The first one is the primary, which is selected also for the gene_name in GTF file
				break;
			}
		}
		
		if (id == null) {
			return null;
		}
		
		//http://beta.rest.ensembl.org/lookup/id/ENSG00000157764?content-type=application/json;format=full
		//{"seq_region_name":"7","object_type":"Gene","db_type":"core","strand":-1,"species":"homo_sapiens","id":"ENSG00000157764","end":140624564,"start":140424943}
		
		jsonData = query(server.getUrl() + "/lookup/id/" + id + "?content-type=application/json;format=full");
		
		Map<String, String> typedMap = getTypedMap(jsonData);
		
		return getRegion(typedMap);
	}
	
	public static List<Species> getSpecies(DataUrl server) throws GBrowserException, IOException {
		
		//http://beta.rest.ensembl.org/info/species?content-type=application/json
		//{"species":[{"division":"Ensembl","groups":["core","otherfeatures","variation","funcgen"],"aliases":["4932","saccer","saccharomyces cerevisiae (baker's yeast)","baker's yeast","scer","saccharomyces cerevisiae","scerevisiae","s_cerevisiae","saccharomyces_cerevisiae ef4"],"name":"saccharomyces_cerevisiae","release":73},
		
		Map jsonData = query(server.getUrl() + "/info/species?content-type=application/json");
		
		List<Map<String, String>> maps = getTypedMaps(jsonData);
		
		List<Species> speciesList = new LinkedList<Species>();
		
		for (Map<String, String> map : maps) {
			Species species = new Species();
			species.setName(map.get("name"));
			species.setRelease(map.get("release"));
			
			//species.setCoordSystem(getCoordSystem(server, species.getName()));
			
			speciesList.add(species);
		}
		
		return speciesList;
	}
	
	private static String getCoordSystem(DataUrl server, String species) throws GBrowserException, IOException {
		
		//http://beta.rest.ensembl.org/assembly/info/homo_sapiens?content-type=application/json
		//{"karyotype":["1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","X","Y","MT"],"assembly.name":"GRCh37.p12","top_level_seq_region_names":["1","10","11","12","13","14","15","16","17","18","19","2","20","21","22","3","4","5","6","7","8","9","GL000191.1","GL000192.1","GL000193.1","GL000194.1","GL000195.1","GL000196.1","GL000197.1","GL000198.1","GL000199.1","GL000200.1","GL000201.1","GL000202.1","GL000203.1","GL000204.1","GL000205.1","GL000206.1","GL000207.1","GL000208.1","GL000209.1","GL000210.1","GL000211.1","GL000212.1","GL000213.1","GL000214.1","GL000215.1","GL000216.1","GL000217.1","GL000218.1","GL000219.1","GL000220.1","GL000221.1","GL000222.1","GL000223.1","GL000224.1","GL000225.1","GL000226.1","GL000227.1","GL000228.1","GL000229.1","GL000230.1","GL000231.1","GL000232.1","GL000233.1","GL000234.1","GL000235.1","GL000236.1","GL000237.1","GL000238.1","GL000239.1","GL000240.1","GL000241.1","GL000242.1","GL000243.1","GL000244.1","GL000245.1","GL000246.1","GL000247.1","GL000248.1","GL000249.1","MT","X","Y"],"assembly.date":"2009-02","coord_system_versions":["","GRCh37","NCBI36","NCBI34","NCBI35"],"genebuild.start_date":"2010-07-Ensembl","genebuild.initial_release_date":"2011-04","schema_build":"73_37","genebuild.last_geneset_update":"2013-06","genebuild.method":"full_genebuild","default_coord_system_version":"GRCh37"}
		
		Map jsonData = query(server.getUrl() + "/assembly/info/" + species + "?content-type=application/json");
		
		@SuppressWarnings("unchecked")
		Map<String, String> typedMap = jsonData;
		
		return typedMap.get("default_coord_system_version");
	}

	public static class Species {
		private String name;
		private String release;
		private String coordSystem;
		
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public String getRelease() {
			return release;
		}
		public void setRelease(String release) {
			this.release = release;
		}
		public String getCoordSystem() {
			return coordSystem;
		}
		public void setCoordSystem(String coordSystem) {
			this.coordSystem = coordSystem;
		}
		
		public String toString() {
			return name + "\t" + release + "\t" + coordSystem; 
		}
		public String getDisplayName() {
			String displayName = name.replace("_", " ");
			displayName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
			return displayName;
		}
	}
	
	public static Map regionQuery(String endpoint, DataRequest request, String query) throws GBrowserException, IOException {

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
	    
	    return query(urlString);
	}
	
	/**
	 * There are two different speed limits
	 * 
	 * 
	 * @param urlString
	 * @return
	 * @throws GBrowserException
	 * @throws IOException
	 */
	public static Map query(String urlString) throws GBrowserException, IOException {
		
		long t = System.currentTimeMillis();
	    
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
						Thread.sleep(retryAfter * 1000);
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
	    
	    //TODO replace json parser with something that is able to handle empty lists. Workaround for the meantime:
	    output = output.replace("[]", "[\"\"]");
	    output = output.replace("\"\"", "\"null\"");
	    
	    //System.out.println("" + (System.currentTimeMillis() - t) + "ms " + output.length() + " bytes");
	    
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
	    	long earliestTime = timeOfLastRequest + wait;
	    	
	    	long waitTime = earliestTime - now;
	    	if (waitTime > 0) {
	    		System.out.println("Rest api request speed limit exceeded, waiting " + waitTime + " ms before next request.");
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
	   
	    //System.out.println("Reset: " + secondsUntilReset + "\tRemaining: " + remainingRequestCount + "\tWait was: " + wait);
	    
	    if (remainingTimeRatio > remainingRequestRatio) {
	    	wait = wait * 2;
	    } else { 
	    	wait = Math.max(MIN_WAIT, wait / 2);
	    }
	    
	    timeOfLastRequest = System.currentTimeMillis();
	}


	public static Region getRegion(Map<String, String> map) {
		
		String startString = map.get("start");
		String endString = map.get("end");
		String chrString = map.get("seq_region_name");

		long start = Long.parseLong(startString);
		long end = Long.parseLong(endString);
		Chromosome chr = new Chromosome(chrString);

		Region region = new Region(start, end, chr);
		return region;
	}
	

	public static Strand getStrand(Map<String, String> map) {
		String strandString = map.get("strand");
		Strand strand = null;
		if ("1".equals(strandString)) {
			strand = Strand.FORWARD;
		}
		if ("-1".equals(strandString)) {
			strand = Strand.REVERSE;
		}
		if ("0".equals(strandString)) {
			strand = Strand.NONE;
		}
		return strand;
	}
}

package fi.csc.microarray.client.visualisation.methods.gbrowser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;

import sun.org.mozilla.javascript.internal.json.JsonParser;

import com.json.parsers.JSONParser;
import com.json.parsers.JsonParserFactory;

public class RestTest {
	public static void main(String[] args) throws IOException {
	
		    String server = "http://beta.rest.ensembl.org";
		    String ext = "/feature/region/human/7:140424943-140624564?feature=gene;feature=transcript;feature=cds;feature=exon";
		    URL url = new URL(server + ext);
		 
		    URLConnection connection = url.openConnection();
		    HttpURLConnection httpConnection = (HttpURLConnection)connection;
		    connection.setRequestProperty("Content-Type", "application/json");
		 
		    InputStream response = connection.getInputStream();
		    int responseCode = httpConnection.getResponseCode();
		 
		    if(responseCode != 200) {
		      throw new RuntimeException("Response code was not 200. Detected response was "+responseCode);
		    }
		 
		    String output;
		    Reader reader = null;
		    try {
		      reader = new BufferedReader(new InputStreamReader(response, "UTF-8"));
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
		    
		    JsonParserFactory factory = JsonParserFactory.getInstance();
		    JSONParser parser=factory.newJsonParser();
		    Map jsonData=parser.parseJson(output);

		    print(jsonData);
	}

	private static void print(Map jsonData) {
		for (Object keyObj : jsonData.keySet()) {
	    	String key = (String) keyObj;
	    	Object valueObj = jsonData.get(keyObj);
	    	if (valueObj instanceof String) {
				String valueString = (String) valueObj;
				System.out.println(key + "\t" + valueString);					
			}
	    	if (valueObj instanceof List) {
				List valueList = (List) valueObj;
				for (Object mapObj : valueList) {
					if (mapObj instanceof Map) {
						Map map = (Map) mapObj;
						print(map);
					}
				}
			}
	    }
	}
}

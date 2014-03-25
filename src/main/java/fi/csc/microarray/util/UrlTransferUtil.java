package fi.csc.microarray.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.jms.JMSException;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.DefaultHttpClient;
import org.eclipse.jetty.util.ajax.JSON;

import fi.csc.microarray.filebroker.ChecksumException;
import fi.csc.microarray.security.CryptoKey;

public class UrlTransferUtil {
	
	public static String uploadBoundary = CryptoKey.generateRandom();
	
	public static class UploadResponse {
		private String dataId;
		private String checksum;
		
		public UploadResponse(String dataId, String checksum) {
			this.dataId = dataId;
			this.checksum = checksum;
		}
		
		public String getDataId() {
			return dataId;
		}
		
		public void setDataId(String dataId) {
			this.dataId = dataId;
		}

		public String getChecksum() {
			return checksum;
		}

		public void setChecksum(String checksum) {
			this.checksum = checksum;
		}
	}

	public static int HTTP_TIMEOUT_MILLISECONDS = 2000;

	private static final int CHUNK_SIZE = 2048;

	public static InputStream downloadStream(URL url) throws JMSException, IOException {
		return url.openStream();		
	}

	
	public static String parseFilename(URL url) {
		int start = url.getPath().contains("/") ? url.getPath().lastIndexOf("/") + 1 : url.getPath().length();
		return url.getPath().substring(start);
	}

	
	/**
	 * Uploads a file (or similar) over HTTP.
	 * NOTE! Compression does not work with files larger than 4 gigabytes
	 * in JDK 1.6 and earlier.
	 *  
	 * @param url address to copy to
	 * @param fis source to copy from
	 * @param useChunked use HTTP 1.1 chunked mode?
	 * @param progressListener can be null
	 * @return
	 * @throws JMSException
	 * @throws IOException
	 * @throws ChecksumException 
	 */
    public static UploadResponse uploadStream(URL url, InputStream fis, boolean useChunked, boolean compress, boolean useChecksums, IOUtils.CopyProgressListener progressListener) throws IOException, ChecksumException {

    	String dataId = null;
    	String checksum = null;
    	
    	//FIXME error handling, temp file etc.
    	
//    	File tempFile = File.createTempFile("chipster-upload-tmp", "");
//    	
//    	String filename = UrlTransferUtil.parseFilename(url);
//    	
//    	try (FileOutputStream out = new FileOutputStream(tempFile)) {
//    		IOUtils.copy(fis, out);
//    	}

		HttpClient httpClient = new DefaultHttpClient();
		HttpPost httpPost;
		try {
			httpPost = new HttpPost(url.toURI());			
			MultipartEntityBuilder multipart = MultipartEntityBuilder.create();
			
			// There are three alternatives for sending the data. Server implementation at
			// restviews.py expects a part to be named as "file". 

			// 1. Input stream
			// this will use Transfer-Encoding: chunked, because Content-Length is not known
//			multipart.addBinaryBody("file", fis);
			
			// 2. File
			// sets ContentType "application/octet-stream" automatically, and sends the filename of
			// the original file
//			multipart.addBinaryBody("file", tempFile);
			
			// 3. Byte array
			// requires an explicit ContentType definition
			byte[] bytes = org.apache.commons.io.IOUtils.toByteArray(fis);
			multipart.addBinaryBody("file", bytes, ContentType.DEFAULT_BINARY, "filename");			
			
			httpPost.setEntity(multipart.build());	
			
			HttpResponse response = httpClient.execute(httpPost);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
				//FIXME
				System.err.println(response.getStatusLine());				
			}
			
			String responseBody = org.apache.commons.io.IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			
    		//FIXME use json parser
    		//dataId = responseBody.replace("{\n  \"id\": \"", "").replace("\"\n}", "");
			dataId = parseJsonId(responseBody);
			
    		System.out.println(dataId);
    		
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

//    	HttpURLConnection connection = null;
//    	
//    	try {
//    		connection = prepareForUpload(url);
//
//    		if (useChunked) {
//    			// use chunked mode or otherwise URLConnection loads everything into memory
//    			// (chunked mode not supported before JRE 1.5)
//    			connection.setChunkedStreamingMode(CHUNK_SIZE);
//    		}
//    		
//    		ChecksumInputStream is = null;
//    		OutputStream os = null;
//    		
//    		try {
//    			is = new ChecksumInputStream(fis, useChecksums, connection);    					    			
//    			
//    			if (compress) {
//        			Deflater deflater = new Deflater(Deflater.BEST_SPEED);
//        			os = new DeflaterOutputStream(connection.getOutputStream(), deflater);
//    			} else {
//    				os = connection.getOutputStream();	
//    			}
//    			
//    			IOUtils.copy(is, os, progressListener);
//    			
//    		} catch (IOException e) {
//    			e.printStackTrace();
//    			throw e;
//
//    		} finally {
//    			IOUtils.closeIfPossible(is);
//    			IOUtils.closeIfPossible(os);
//    		}
//    		
//    		if (!isSuccessfulCode(connection.getResponseCode())) {
//    			throw new IOException("POST was not successful: "
//    					+ connection.getResponseCode() + " " + connection.getResponseMessage());
//    		}
//    		
//    		checksum = is.verifyChecksums();    		
//    		
//		} finally {
//    		IOUtils.disconnectIfPossible(connection);
//    	}
//
//    	//checksum may be null
    	return new UploadResponse(dataId, checksum);
    }
    
    private static String parseJsonId(String json) {
    	
    	@SuppressWarnings({ "rawtypes", "unchecked" })
		Map<String, String> rootMap = (Map) JSON.parse(json);
    	return rootMap.get("id");
	}


	public static boolean isSuccessfulCode(int responseCode) {
		return responseCode >= 200 && responseCode < 300; // 2xx => successful
	}
    

    /**
     * Overrides system proxy settings (JVM level) to always bypass the proxy.
     * This method must be called BEFORE any upload URL objects are created.
     * It is required because JRE does not respect at all the proxy parameter
     * given to URL.openConnection(Proxy), which would be the good solution
     * for overriding proxies for uploads.
     * 
     * @see java.net.URL#openConnection(Proxy)
     */
	public static void disableProxies() {

		ProxySelector.setDefault(new ProxySelector() {

			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
				// we are not interested in this
			}

			@Override
			public List<Proxy> select(URI uri) {
                LinkedList<Proxy> proxies = new LinkedList<Proxy>();
                proxies.add(Proxy.NO_PROXY);
                return proxies;
			}
	    	
	    });
	}

	public static HttpURLConnection prepareForUpload(URL url) throws IOException {
		HttpURLConnection connection = (HttpURLConnection)url.openConnection(); // should use openConnection(Proxy.NO_PROXY) if it actually worked
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + uploadBoundary);
		return connection;
	}

	public static boolean isAccessible(URL url) throws IOException {
		// check the URL
		HttpURLConnection connection = (HttpURLConnection)url.openConnection();
		connection.setConnectTimeout(HTTP_TIMEOUT_MILLISECONDS);
		connection.connect() ; 
		return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
	}


	public static long getContentLength(URL url) throws IOException {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection)url.openConnection();
			return Long.parseLong(connection.getHeaderField("content-length"));
		} finally {
			IOUtils.disconnectIfPossible(connection);
		}
	}
}

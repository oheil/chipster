package fi.csc.microarray.filebroker;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.InflaterInputStream;

import javax.jms.JMSException;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.log4j.Logger;
import org.eclipse.jetty.util.ajax.JSON;

import fi.csc.microarray.client.session.SessionSaver;
import fi.csc.microarray.config.DirectoryLayout;
import fi.csc.microarray.databeans.DataManager;
import fi.csc.microarray.messaging.MessagingTopic;
import fi.csc.microarray.messaging.UrlListMessageListener;
import fi.csc.microarray.util.Files;
import fi.csc.microarray.util.IOUtils;
import fi.csc.microarray.util.IOUtils.CopyProgressListener;
import fi.csc.microarray.util.UrlTransferUtil;
import fi.csc.microarray.util.UrlTransferUtil.UploadResponse;

/**
 * Client interface for the file broker. Used by client and computing service or
 * anyone who needs transfer files within Chipster. 
 * 
 * Mostly used along the PayloadMessages which carry the URLs for the files.
 * 
 * @author hupponen
 *
 */
public class DaicFileBrokerClient implements FileBrokerClient {
	
//	private static final int SPACE_REQUEST_TIMEOUT = 300; // seconds
	private static final int QUICK_POLL_OPERATION_TIMEOUT = 5; // seconds 
//	private static final int MOVE_FROM_CACHE_TO_STORAGE_TIMEOUT = 24; // hours 
	
	private static final Logger logger = Logger.getLogger(DaicFileBrokerClient.class);
	
//	private MessagingTopic filebrokerTopic;	
	private boolean useChunked;
	private boolean useCompression;
	private String localFilebrokerPath;
	private boolean useChecksums;
	
	public DaicFileBrokerClient(MessagingTopic urlTopic, String localFilebrokerPath) throws JMSException {

//		this.filebrokerTopic = urlTopic;
		this.localFilebrokerPath = localFilebrokerPath;
		
		// Read configs
		this.useChunked = DirectoryLayout.getInstance().getConfiguration().getBoolean("messaging", "use-chunked-http"); 
		this.useCompression = DirectoryLayout.getInstance().getConfiguration().getBoolean("messaging", "use-compression");
		this.useChecksums = DirectoryLayout.getInstance().getConfiguration().getBoolean("messaging", "use-checksums");
		
	}
	
	public DaicFileBrokerClient(MessagingTopic urlTopic) throws JMSException {
		this(urlTopic, null);
	}

	/**
	 * Add file to file broker. Must be a cached file, for other types, use other versions of this method.
	 * 
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#addFile(File, CopyProgressListener)
	 */
	@Override
	public void addFile(String dataId, FileBrokerArea area, File file, CopyProgressListener progressListener) throws FileBrokerException, JMSException, IOException {
		
		if (area != FileBrokerArea.CACHE) {
			throw new UnsupportedOperationException();
		}
		
		if (file.length() > 0 && !this.requestDiskSpace(file.length())) {
			throw new NotEnoughDiskSpaceException();
		}
		
		// get new url
		//FIXME
		URL url = getUploadURL(null, useCompression, FileBrokerArea.CACHE, file.length());
		if (url == null) {
			throw new FileBrokerException("filebroker is not responding");
		}

		// try to move/copy it locally, or otherwise upload the file
		if (localFilebrokerPath != null && !useCompression) {
			String filename = dataId;
			File dest = new File(localFilebrokerPath, filename);
			boolean success = file.renameTo(dest);
			if (!success) {
				IOUtils.copy(file, dest); // could not move (different partition etc.), do a local copy
			}

		} else {
			InputStream stream = new FileInputStream(file);
			try {
				UploadResponse response = uploadStream(url, stream, useChunked, useCompression, useChecksums, progressListener);
				logger.debug("successfully uploaded: " + url + "\tlength: " + file.length() + "\tmd5: " + response.getChecksum());
			} catch (ChecksumException e) {
				// corrupted data or data id collision
				throw new IOException(e);
			} finally {
				IOUtils.closeIfPossible(stream);
			}
		}
	}
	
	/**
	 * @return DataBean with dataId and md5 String of the uploaded data (md5 only if enabled in configuration)
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#addFile(InputStream, CopyProgressListener)
	 */
	@Override
	public UploadResponse addFile(String filename, String sessionId, FileBrokerArea area, InputStream file, long contentLength, CopyProgressListener progressListener) throws FileBrokerException, JMSException, IOException {
		
		URL url;
		if (area == FileBrokerArea.CACHE) {
			if (contentLength > 0  && !this.requestDiskSpace(contentLength)) {
				throw new NotEnoughDiskSpaceException();
			}

			// Get new url
			url = getUploadURL(sessionId, useCompression, FileBrokerArea.CACHE, contentLength);
			if (url == null) {
				throw new FileBrokerException("New URL is null.");
			}
			
		} else {
			// Get new url
			url = getUploadURL(sessionId, useCompression, FileBrokerArea.STORAGE, contentLength);
			if (url == null) {
				throw new FileBrokerException("New URL is null.");
			}			
		}

		// Upload the stream into a file at filebroker
		logger.debug("uploading new file: " + url);
		UploadResponse response;
		try {
			response = uploadStream(filename, url, file, useChunked, useCompression, useChecksums, progressListener);
			response.setDataId(sessionId + "/" + response.getDataId());
		} catch (ChecksumException e) {
			// corrupted data or data id collision
			throw new IOException(e);
		}
		logger.debug("successfully uploaded: " + url + "\tlength: " + contentLength + "\tmd5: " + response);
		
		return response;
	}
	
	@Override
	public ChecksumInputStream getInputStream(String dataId) throws IOException, JMSException {
		URL url = null;
		try {
			url = getDownloadURL(dataId);
		} catch (FileBrokerException e) {
			logger.error(e);
		}
		
		if (url == null) {
			throw new FileNotFoundException("file not found: " + dataId);
		}
		
		InputStream payload = null;

		URLConnection connection = null;
		try {
			// make sure http cache is disabled
			connection = url.openConnection();
			connection.setUseCaches(false);
			connection.connect();						

			// open stream
			payload = connection.getInputStream();

			// wait for payload to become available
			long waitStartTime = System.currentTimeMillis();
			int waitTime = 10;
			while (payload.available() < 1 && (waitStartTime + QUICK_POLL_OPERATION_TIMEOUT*1000 > System.currentTimeMillis())) {
				// sleep
				try {
					Thread.sleep(waitTime);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
		} catch (Exception e) {
			IOUtils.closeIfPossible(payload);
			throw e;
		}

		// detect compression
		
		InputStream stream = payload;
		if (url.toString().endsWith(".compressed")) {
			stream = new InflaterInputStream(payload);
		}
		
		return new ChecksumInputStream(stream, useChecksums, connection);			
	}

	
	/**
	 * Get a local copy of a file. If the dataId  matches any of the files found from 
	 * local filebroker paths (given in constructor of this class), then it is symlinked or copied locally.
	 * Otherwise the file pointed by the dataId is downloaded.
	 * @throws JMSException 
	 * @throws ChecksumException 
	 * 
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#getFile(File, URL)
	 */
	@Override
	public void getFile(String dataId, File destFile) throws IOException, JMSException, ChecksumException {
		
		// Try to find the file locally and symlink/copy it
		if (localFilebrokerPath != null) {
			
			// If file in filebroker cache is compressed, it will have specific suffix and we will not match it
			File fileInFilebrokerCache = new File(localFilebrokerPath, dataId);
			
			if (fileInFilebrokerCache.exists()) {
				boolean linkCreated = Files.createSymbolicLink(fileInFilebrokerCache, destFile);
	
				if (!linkCreated) {
					IOUtils.copy(fileInFilebrokerCache, destFile); // cannot create a link, must copy
				}
			}
			
		} else {
			// Not available locally, need to download
			ChecksumInputStream inputStream = null;
			OutputStream fileStream = null;
			try {
				// Download to file
				inputStream = getInputStream(dataId);				
				fileStream = new FileOutputStream(destFile);
				
				IOUtils.copy(new BufferedInputStream(inputStream), new BufferedOutputStream(fileStream));
				
				inputStream.verifyChecksums();
				
			} finally {
				IOUtils.closeIfPossible(inputStream);
				IOUtils.closeIfPossible(fileStream);
			}
		}
	}

	@Override
	public boolean isAvailable(String dataId, Long contentLength, String checksum, FileBrokerArea area) throws JMSException {
		
		
		try {
			URL url = getDownloadURL(dataId);
			if (url != null) {
				return UrlTransferUtil.isAccessible(getDownloadURL(dataId));
			}
		} catch (IOException | FileBrokerException e) {
			//TODO more general exceptions
			new JMSException(e.toString());
		}
		return false;
		
//		BooleanMessageListener replyListener = new BooleanMessageListener();  
//		try {
//			
//			CommandMessage requestMessage = new CommandMessage(CommandMessage.COMMAND_IS_AVAILABLE);
//			requestMessage.addNamedParameter(ParameterMessage.PARAMETER_FILE_ID, dataId);
//			requestMessage.addNamedParameter(ParameterMessage.PARAMETER_SIZE, contentLength.toString());
//			requestMessage.addNamedParameter(ParameterMessage.PARAMETER_CHECKSUM, checksum);
//			requestMessage.addNamedParameter(ParameterMessage.PARAMETER_AREA, area.toString());
//			filebrokerTopic.sendReplyableMessage(requestMessage, replyListener);
//			
//			// wait
//			Boolean success = replyListener.waitForReply(QUICK_POLL_OPERATION_TIMEOUT, TimeUnit.SECONDS); 
//			
//			// check how it went
//			
//			// timeout
//			if (success == null) {
//				throw new RuntimeException("timeout while waiting for the filebroker");
//			} else {
//				return success;
//			}
//			
//		} finally {
//			replyListener.cleanUp();
//		}
	}

	
	@Override
	public boolean moveFromCacheToStorage(String dataId) throws JMSException, FileBrokerException {
		logger.debug("moving from cache to storage: " + dataId);
		
		//TODO server side move
		try {
			File tmpFile = File.createTempFile("chipster-move-temp-file", "");
			getFile(dataId, tmpFile);
			addFile(dataId, FileBrokerArea.STORAGE, tmpFile, null);
			tmpFile.delete();
			return true;
		} catch (IOException | ChecksumException e) {
			//TODO more general exceptions
			new JMSException(e.toString());
		}
		return false;
		
	
//		SuccessMessageListener replyListener = new SuccessMessageListener();  
//		try {
//			
//			// ask file broker to move it
//			CommandMessage moveRequestMessage = new CommandMessage(CommandMessage.COMMAND_MOVE_FROM_CACHE_TO_STORAGE);
//			moveRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_FILE_ID, dataId);
//			filebrokerTopic.sendReplyableMessage(moveRequestMessage, replyListener);
//			
//			// wait
//			SuccessMessage successMessage = replyListener.waitForReply(MOVE_FROM_CACHE_TO_STORAGE_TIMEOUT, TimeUnit.HOURS); 
//			
//			// check how it went
//			
//			// timeout
//			if (successMessage == null) {
//				throw new RuntimeException("timeout while waiting for the filebroker");
//			} else if (FileServer.ERROR_QUOTA_EXCEEDED.equals(successMessage.getErrorMessage())) {
//				throw new QuotaExceededException();
//			} else {
//				return successMessage.success();
//			}
//			
//		} finally {
//			replyListener.cleanUp();
//		}
	}
	
	/**
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#getPublicFiles()
	 */
	@Override
	public List<URL> getPublicFiles() throws JMSException {
		return fetchPublicFiles();
	}

	private List<URL> fetchPublicFiles() throws JMSException {

		UrlListMessageListener replyListener = new UrlListMessageListener();  
		List<URL> urlList = new ArrayList<URL>();
//		try {
//			CommandMessage fileRequestMessage = new CommandMessage(CommandMessage.COMMAND_PUBLIC_FILES_REQUEST);
//			
//			filebrokerTopic.sendReplyableMessage(fileRequestMessage, replyListener);
//			urlList = replyListener.waitForReply(QUICK_POLL_OPERATION_TIMEOUT, TimeUnit.SECONDS);
//		} finally {
//			replyListener.cleanUp();
//		}

		return urlList;
	}

	@Override
	public boolean requestDiskSpace(long size) throws JMSException {

//		BooleanMessageListener replyListener = new BooleanMessageListener();  
//		Boolean spaceAvailable;
//		try {
//			CommandMessage spaceRequestMessage = new CommandMessage(CommandMessage.COMMAND_DISK_SPACE_REQUEST);
//			spaceRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_DISK_SPACE, String.valueOf(size));
//			filebrokerTopic.sendReplyableMessage(spaceRequestMessage, replyListener);
//			spaceAvailable = replyListener.waitForReply(SPACE_REQUEST_TIMEOUT, TimeUnit.SECONDS);
//		} finally {
//			replyListener.cleanUp();
//		}
//
//		if (spaceAvailable == null) {
//			logger.warn("did not get response for space request");
//			return false;
//		}
//		return spaceAvailable;
		
		return true;
	}

	@Override
	public void saveRemoteSession(String sessionName, String sessionId, LinkedList<String> dataIds) throws JMSException {		
		
//		ReplyMessageListener replyListener = new ReplyMessageListener();  
//		try {
//			CommandMessage storeRequestMessage = new CommandMessage(CommandMessage.COMMAND_STORE_SESSION);
//			storeRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_SESSION_NAME, sessionName);
//			storeRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_SESSION_UUID, sessionId);
//			storeRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_FILE_ID_LIST, Strings.delimit(dataIds, "\t"));
//			
//			filebrokerTopic.sendReplyableMessage(storeRequestMessage, replyListener);
//			ParameterMessage reply = replyListener.waitForReply(QUICK_POLL_OPERATION_TIMEOUT, TimeUnit.SECONDS);
//			
//			if (reply == null || !(reply instanceof CommandMessage) || !CommandMessage.COMMAND_FILE_OPERATION_SUCCESSFUL.equals((((CommandMessage)reply).getCommand()))) {
//				throw new JMSException("failed to save session metadata remotely");
//			}
//			
//		} finally {
//			replyListener.cleanUp();
//		}
	}
	
	public Map<String, String> listFiles(String sessionId) {
								
		Map<String, String> files = new HashMap<>();
		
		HttpClient httpClient = new DefaultHttpClient();
		HttpGet request = new HttpGet("http://127.0.0.1:5000/v1/containers/" + sessionId + "/files");			

		HttpResponse response;
		try {
			response = httpClient.execute(request);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
				//FIXME
				System.err.println(response.getStatusLine());				
			}

			String responseBody = org.apache.commons.io.IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			
			Map rootMap = (Map) JSON.parse(responseBody);
			
			Object[] fileArray = (Object[]) rootMap.get("files");
			
			for (Object fileObj : fileArray) {
				Map fileMap = (Map) fileObj;
								
				files.put(fileMap.get("id").toString(), fileMap.get("name").toString());
			}		
	    	
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return files;
	}

	@Override
	public List<DbSession> listRemoteSessions() throws JMSException {
								
		List<DbSession> sessions = new LinkedList<>();
		
		HttpClient httpClient = new DefaultHttpClient();
		HttpGet request = new HttpGet("http://127.0.0.1:5000/v1/containers");			

		HttpResponse response;
		try {
			response = httpClient.execute(request);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
				//FIXME
				System.err.println(response.getStatusLine());				
			}

			String responseBody = org.apache.commons.io.IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			
			Object object = JSON.parse(responseBody);
			
			if (object instanceof Object[]) {
				Object[] objArray = (Object[]) object;
				
				for (Object mapObj : objArray) {
					if (mapObj instanceof Map) {
						@SuppressWarnings("unchecked")
						Map<String, String> container = (Map) mapObj;
						
						DbSession session = new DbSession(container.get("id"), container.get("name"), "user");
						sessions.add(session);
					}
				}
			}				
	    	
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return sessions;
	}
	

	@Override
	public List<DbSession> listPublicRemoteSessions() throws JMSException {
				
		List<DbSession> allSessions = listRemoteSessions();
		List<DbSession> publicSessions = new LinkedList<>();
		
		for (DbSession session : allSessions) {
			if (session.getName().startsWith(DerbyMetadataServer.DEFAULT_EXAMPLE_SESSION_FOLDER)) {
				publicSessions.add(session);
			}
		}		
		return publicSessions;
	}

	@Override
	public void removeRemoteSession(String dataId) throws JMSException {
		
//		SuccessMessageListener replyListener = new SuccessMessageListener();  
//		
//		try {
//			CommandMessage removeRequestMessage = new CommandMessage(CommandMessage.COMMAND_REMOVE_SESSION);
//			removeRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_SESSION_UUID, dataId); 
//			filebrokerTopic.sendReplyableMessage(removeRequestMessage, replyListener);
//			SuccessMessage reply = replyListener.waitForReply(QUICK_POLL_OPERATION_TIMEOUT, TimeUnit.SECONDS);
//			
//			if (reply == null || !reply.success()) {
//				throw new JMSException("failed to remove session");
//			}
//			
//		} finally {
//			replyListener.cleanUp();
//		}
	}
	
	/**
	 * Get an URL for a new file from the file broker.
	 * 
	 * Talks to the file broker using JMS.
	 * 
	 * If useCompression is true, request an url ending with .compressed.
	 * NOTE! Compression does not work with files larger than 4 gigabytes
	 * in JDK 1.6 and earlier.
	 * @param sessionId 
	 *  
	 * @return the new URL, may be null if file broker sends null or
	 * if reply is not received before timeout
	 * 
	 * @throws JMSException
	 * @throws FileBrokerException 
	 */
	private URL getUploadURL(String sessionId, boolean useCompression, FileBrokerArea area, long contentLength) throws JMSException, FileBrokerException {
		
		// FIXME how about the container id?
		try {
			return new URL("http://127.0.0.1:5000/v1/containers/" + sessionId + "/files");
		} catch (MalformedURLException e) {
			new FileBrokerException(e);
		}
		return null;
	}

	private URL getDownloadURL(String dataId) throws JMSException, FileBrokerException {
		
		if (!dataId.contains("/")) {
			return null;
		}
		String[] split  = dataId.split("/");
		String sessionId = split[0];
		dataId = split[1];
		
		try {
			return new URL("http://127.0.0.1:5000/v1/containers/" + sessionId + "/files/" + dataId + "/download");
		} catch (MalformedURLException e) {
			new FileBrokerException(e);
		}
		return null;
	}

	@Override
	public String getExternalURL(String dataId) throws JMSException, FileBrokerException {
		return getDownloadURL(dataId).toExternalForm();
	}

	@Override
	public Long getContentLength(String dataId) throws IOException, JMSException, FileBrokerException {
		URL url = getDownloadURL(dataId);
		if (url == null) {
			return null;
		}
		return UrlTransferUtil.getContentLength(url);
	}

	@Override
	public String createContainer(String name) {
		
		String sessionId = null;
		
		HttpClient httpClient = new DefaultHttpClient();
		HttpPost httpPost = new HttpPost("http://127.0.0.1:5000/v1/containers");			
		MultipartEntityBuilder multipart = MultipartEntityBuilder.create();			
		multipart.addTextBody("name", name);						
		httpPost.setEntity(multipart.build());	

		HttpResponse response;
		try {
			response = httpClient.execute(httpPost);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
				//FIXME
				System.err.println(response.getStatusLine());				
			}

			String responseBody = org.apache.commons.io.IOUtils.toString(response.getEntity().getContent(), "UTF-8");

			sessionId = parseJsonUuid(responseBody);

			System.out.println("container created: " + sessionId);    			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return sessionId;
	}
	
    public static UploadResponse uploadStream(String filename, URL url, InputStream fis, boolean useChunked, boolean compress, boolean useChecksums, IOUtils.CopyProgressListener progressListener) throws IOException, ChecksumException {

    	String dataId = null;
    	String checksum = null;

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
			multipart.addBinaryBody("file", bytes, ContentType.DEFAULT_BINARY, filename);			
			
			httpPost.setEntity(multipart.build());	
			
			HttpResponse response = httpClient.execute(httpPost);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
				//FIXME
				System.err.println(response.getStatusLine());				
			}
			
			String responseBody = org.apache.commons.io.IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			
			dataId = parseJsonId(responseBody);
			
    		System.out.println(dataId);
    		
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

    	//checksum may be null
    	return new UploadResponse(dataId, checksum);
    }
    
    @Override
    public String getSessionZipId(String sessionId) {
    	Map<String, String> files = listFiles(sessionId);
    	for (Entry<String, String> entry : files.entrySet()) {
    		if (SessionSaver.REMOTE_SESSION_FILENAME.equals(entry.getValue())) {
    			return sessionId + "/" + entry.getKey();
    		}
    	}
		return null;
    }
    
    public static String parseJsonId(String json) {    	
    	return getJsonValue(json, "id");
	}
    
    public static String parseJsonUuid(String json) {    	
    	return getJsonValue(json, "uuid");
	}
    
    public static String getJsonValue(String json, String key) {
    	@SuppressWarnings({ "rawtypes", "unchecked" })
		Map<String, String> rootMap = (Map) JSON.parse(json);
    	return rootMap.get(key);
    }
}

package fi.csc.microarray.analyser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.springframework.validation.Errors;

import fi.csc.microarray.analyser.AnalysisTestBase.JobResultListener;
import fi.csc.microarray.client.ClientApplication;
import fi.csc.microarray.client.RemoteServiceAccessor;
import fi.csc.microarray.client.ServiceAccessor;
import fi.csc.microarray.client.Session;
import fi.csc.microarray.client.dialog.ChipsterDialog.DetailsVisibility;
import fi.csc.microarray.client.dialog.ChipsterDialog.PluginButton;
import fi.csc.microarray.client.dialog.DialogInfo.Severity;
import fi.csc.microarray.client.operation.Operation;
import fi.csc.microarray.client.operation.OperationDefinition;
import fi.csc.microarray.client.operation.OperationRecord;
import fi.csc.microarray.client.operation.OperationRecord.InputRecord;
import fi.csc.microarray.client.operation.OperationRecord.ParameterRecord;
import fi.csc.microarray.client.operation.ToolCategory;
import fi.csc.microarray.client.operation.ToolModule;
import fi.csc.microarray.client.operation.parameter.DataSelectionParameter;
import fi.csc.microarray.client.operation.parameter.Parameter;
import fi.csc.microarray.client.session.SessionManager;
import fi.csc.microarray.client.tasks.Task;
import fi.csc.microarray.client.tasks.Task.State;
import fi.csc.microarray.client.tasks.TaskExecutor;
import fi.csc.microarray.config.DirectoryLayout;
import fi.csc.microarray.databeans.DataBean;
import fi.csc.microarray.databeans.DataBean.DataNotAvailableHandling;
import fi.csc.microarray.databeans.DataBean.Link;
import fi.csc.microarray.databeans.DataManager;
import fi.csc.microarray.databeans.DataManager.ContentLocation;
import fi.csc.microarray.databeans.DataManager.StorageMethod;
import fi.csc.microarray.exception.MicroarrayException;
import fi.csc.microarray.messaging.MessagingTestBase;
import fi.csc.microarray.module.ModuleManager;
import fi.csc.microarray.module.chipster.MicroarrayModule;
import fi.csc.microarray.util.IOUtils;

public class SessionReplayTest extends MessagingTestBase {

	private static final String FLAG_FILE = "test-ok";
	private static final String DEFAULT_SESSIONS_DIR = "sessions";
	private static final String DEFAULT_WEB_DIR = "web";
	private static final String SCREEN_OUTPUTS_DIR = "screen-outputs";
	
	private static final long TOOL_TEST_TIMEOUT = 3;
	private static final TimeUnit TOOL_TEST_TIMEOUT_UNIT = TimeUnit.HOURS;
	
	private static final boolean FAIL_ON_OUTPUT_SIZE_MISMATCH = false;
	private static final boolean CHECK_CONTENTS = true;
	
	
	private static final String CSS = "<style type=\"text/css\">" + 
			"th {text-align: left; border-bottom-width: 1; border-bottom-style: solid}" +
			"td {padding-right: 1em}" +
			"h3 {margin-top: 2em}" +
			"a {text-decoration: none}" +
			"</style>";
	
	private Date startTime;
	private File sessionsDir;
	static private File webDir;
	
	private List<ToolTestResult> toolTestResults = new LinkedList<ToolTestResult>();
	
	// Sessions which cause something to be thrown, normal tool failures etc not counted
	// TODO add to report
	private LinkedHashMap<File, Throwable> sessionsWithErrors = new LinkedHashMap<File, Throwable>();
	private LinkedHashMap<File, String> sessionsWithMissingTools = new LinkedHashMap<File, String>();
	
	private TaskExecutor executor;
	private DataManager manager, sourceManager;
	private ServiceAccessor serviceAccessor;
	LinkedList<ToolModule> toolModules;
	
	public SessionReplayTest(String username, String password, String configURL) {
		super(username, password, configURL);
		this.startTime = new Date();
	}

	public boolean testSessions(String sessionsDirName) throws Exception {

		this.sessionsDir = new File(sessionsDirName);
		
		// Set up modules
		ModuleManager moduleManager = new ModuleManager("fi.csc.microarray.module.chipster.MicroarrayModule");
		Session.getSession().setModuleManager(moduleManager);
		try {
		// Set up main (target) system
		manager = new DataManager();
		moduleManager.plugAll(manager, null);
		executor = new TaskExecutor(super.endpoint, manager);
		toolModules = new LinkedList<ToolModule>();
		serviceAccessor = new RemoteServiceAccessor();
		serviceAccessor.initialise(manager, this.authenticationListener);
		serviceAccessor.fetchDescriptions(new MicroarrayModule());
		Session.getSession().setServiceAccessor(serviceAccessor);
		toolModules.addAll(serviceAccessor.getModules());
		Session.getSession().setClientApplication(new SessionLoadingSkeletonApplication(this, toolModules, manager));
		
		// Set up source system
		sourceManager = new DataManager();
		moduleManager.plugAll(sourceManager, null);
		
		// Run all sessions
		for (File testSession : sessionsDir.listFiles()) {
			
			// Zip files only, maybe should check if it really is a session file
			if (!testSession.getName().endsWith(".zip")) {
				continue;
			}

			// Clear data managers after previous session
			manager.deleteAllDataItems();
			sourceManager.deleteAllDataItems();
			
			// Test session
			try {
				testSession(testSession);
			} catch (Throwable e) {
				e.printStackTrace();
				sessionsWithErrors.put(testSession, e);
			}
		}
		} finally {
			if (serviceAccessor != null) {
				serviceAccessor.close();
			}
		}

		// Create reports
		createReports();
		
		// Get overall result
		boolean combinedToolsResult = true;
		for (ToolTestResult toolTestResult : toolTestResults) {
			if (toolTestResult.getTestResult().equals(TestResult.FAIL)) {
				combinedToolsResult = false;
			}
		}
		if (!combinedToolsResult) {
			System.out.println("failed tools, failing");
			return false;
		}
		
		// Fail if no results at all
		if (toolTestResults.size() == 0) {
			System.out.println("zero results, failing");
			return false;
		}
		
		// Fail if missing tools
		if (sessionsWithMissingTools.size() > 0) {
			System.out.println("missing tools, failing");
			return false;
		}

		// Fail if sessions with errors
		if (sessionsWithErrors.size() > 0) {
			System.out.println("sessions with errors, failing");
			return false;
		}
		
		return true;
	}

	private void testSession(File session) throws Exception {

		Map<DataBean, DataBean> sourceDataBeanToTargetDataBean = new HashMap<DataBean, DataBean>();

		// Load session
		// some loading code uses Session.getSession().getDataManagers
		Session.getSession().setDataManager(sourceManager);
		SessionManager sourceSessionManager = new SessionManager(sourceManager, serviceAccessor.getFileBrokerClient(), null);
		sourceSessionManager.loadLocalSession(session, false);
		Session.getSession().setDataManager(manager);
		
		// Pick import operations and copy imported data beans to target manager 
		// Also map OperationRecords to outputs TODO check that order is right, might need to traverse links
		LinkedList<OperationRecord> rootLevelOperationRecords = new LinkedList<OperationRecord>();
		Map<OperationRecord, List<DataBean>> outputMap = new HashMap<OperationRecord, List<DataBean>>();
		LinkedList<DataBean> dataBeansInSourceManagerWhichWereCopied = new LinkedList<DataBean>();
		for (DataBean dataBean : sourceManager.databeans()) {
			OperationRecord operationRecord = dataBean.getOperationRecord();

			// pick import operations, local operations 
			// and those which did have inputs when run, but don't have parents now (i.e. inputs have
			// been deleted for example to save space)
			if (OperationDefinition.IMPORT_DEFINITION_ID.equals(operationRecord.getNameID().getID()) ||
					"LocalNGSPreprocess.java".equals(operationRecord.getNameID().getID()) ||
					(dataBean.getLinkTargets(Link.derivationalTypes()).size() == 0 &&
					operationRecord.getInputs().size() > 0)) {
				
				// load imported databean, add mapping
				DataBean dataBeanCopy = manager.createDataBean(dataBean.getName());
				URL urlInSessionZip = null;
				for (ContentLocation contentLocation : sourceManager.getContentLocationsForDataBeanSaving(dataBean)) {
					if (contentLocation.getMethod() == StorageMethod.LOCAL_SESSION_ZIP) {
						urlInSessionZip = contentLocation.getUrl(); 
					}
				}
				
				if (urlInSessionZip == null) {
					throw new IllegalArgumentException("session file " + session.getName() + " must contain all data files (missing " + dataBean.getName() + ")");
				}
				URL url = new URL(session.toURI().toURL(), "#" + urlInSessionZip.getRef());
				manager.addContentLocationForDataBean(dataBeanCopy, StorageMethod.LOCAL_SESSION_ZIP, url);

				sourceDataBeanToTargetDataBean.put(dataBean, dataBeanCopy);
				dataBeansInSourceManagerWhichWereCopied.add(dataBean);
				
				// avoid NPE 
				dataBeanCopy.setOperationRecord(operationRecord);
				
				// TODO what if not in the root folder in the source manager
				manager.connectChild(dataBeanCopy, manager.getRootFolder());
				rootLevelOperationRecords.add(operationRecord);
			}

			// store output mappings
			List<DataBean> outputs = outputMap.get(operationRecord);
			if (outputs != null) {
				outputs.add(dataBean);
			} else {
				outputs = new LinkedList<DataBean>();
				outputs.add(dataBean);
				outputMap.put(operationRecord, outputs);
			}
		}

		
		// Add links between copied (imported, without parents) data beans
		for (DataBean originalBean : dataBeansInSourceManagerWhichWereCopied) {
			for (Link linkType : Link.values()) {
				for (DataBean originalLinkSourceBean : originalBean.getLinkSources(linkType)) {
					if (dataBeansInSourceManagerWhichWereCopied.contains(originalLinkSourceBean)) {
						sourceDataBeanToTargetDataBean.get(originalLinkSourceBean).addLink(linkType, sourceDataBeanToTargetDataBean.get(originalBean));
					}
				}
			}
		}
		
		
		// Get operation records, avoid duplicates for tools with many outputs
		LinkedList<OperationRecord> operationRecords = new LinkedList<OperationRecord>();
		for (DataBean dataBean : sourceManager.databeans()) {
			OperationRecord operationRecord = dataBean.getOperationRecord();
			if (!operationRecords.contains(operationRecord)) {
				operationRecords.add(operationRecord);
			}
		}
	
		// Run operations in the order they were run originally
		for (OperationRecord operationRecord : operationRecords) {

			// Skip import operations
			if (rootLevelOperationRecords.contains(operationRecord)) {
				System.out.println("skipping root level operation " + operationRecord.getFullName());
				continue;
			}

			System.out.println("setting up " + operationRecord.getFullName());

			// Get inputs
			LinkedList <DataBean> inputBeans = new LinkedList<DataBean>();
			for (InputRecord inputRecord : operationRecord.getInputs()) {
				DataBean inputBean = sourceDataBeanToTargetDataBean.get(inputRecord.getValue());
				if (inputBean != null) {
					inputBeans.add(inputBean);
				} else {
					String s = "not enough inputs for " + operationRecord.getFullName();
					System.out.println(s);
					throw new RuntimeException(s);
				}
			}

			// Set up task
			OperationDefinition operationDefinition = getOperationDefinition(operationRecord.getNameID().getID(), toolModules);
			if (operationDefinition == null) {
				System.out.println("Missing tool: " + operationRecord.getNameID().getID() + "  in session: " + session.getName() + " skipping rest of the session");
				sessionsWithMissingTools.put(session, operationRecord.getNameID().getID());
				return;
			}
			Operation operation = new Operation(operationDefinition, inputBeans.toArray(new DataBean[] {}));

			// Parameters, copy paste from workflows
			for (ParameterRecord parameterRecord : operationRecord.getParameters()) {
				if (parameterRecord.getValue() != null && !parameterRecord.getValue().equals("")) {	
					Parameter definitionParameter = operation.getDefinition().getParameter(parameterRecord.getNameID().getID()); 
					if (definitionParameter != null) {
						Parameter parameter = (Parameter)definitionParameter.clone();
						if (parameter instanceof DataSelectionParameter) {
							((DataSelectionParameter)parameter).parseValueAndSetWithoutChecks(parameterRecord.getValue());
						} else {
							parameter.parseValue(parameterRecord.getValue());
						}

						// Set it
						operation.setParameter(parameter.getID(), parameter.getValue());
					}
				}
			}

			Task task = executor.createTask(operation);
			
			// Execute the task
			System.out.println("running " + operation.getDefinition().getFullName());
			CountDownLatch latch = new CountDownLatch(1);
			task.addTaskEventListener(new JobResultListener(latch));
			executor.startExecuting(task);
			latch.await(TOOL_TEST_TIMEOUT, TOOL_TEST_TIMEOUT_UNIT);
			
			// Task failed
			if (!task.getState().equals(State.COMPLETED)) {
				
				// try to cancel if still running
				if (task.getState().equals(State.RUNNING)) {
					executor.kill(task);
					toolTestResults.add(new ToolTestResult(TestResult.FAIL, operation, session, task, "task did not finish before test timeout " +TOOL_TEST_TIMEOUT + " " + TOOL_TEST_TIMEOUT_UNIT.toString()));
					return;
				}
				toolTestResults.add(new ToolTestResult(TestResult.FAIL, operation, session, task, "task was not completed"));
				return;
			}
			
			// Check results
			// Target manager needs to be available through session for some of these to work
			Session.getSession().setDataManager(manager);
			try {
				
				// Link result beans, add to folders etc
				Session.getSession().getApplication().onFinishedTask(task, operation, task.getState());

				// Check that number of results and result names match
				Iterator<DataBean> targetIterator = task.outputs().iterator();
				for (DataBean sourceBean : outputMap.get(operationRecord)) {
					if (targetIterator.hasNext()) {
						DataBean targetBean = targetIterator.next();

						// check content types
						if (!sourceBean.getContentType().getType().equals(targetBean.getContentType().getType())) {
							toolTestResults.add(new ToolTestResult(TestResult.FAIL, operation, session, task, "Mismatch in result content types, "
									+ sourceBean.getName() + ": " + sourceBean.getContentType().getType() + ", " + targetBean.getName() + ": " + targetBean.getContentType().getType()));
							return;
						}
						
//						// check names
//						if (!sourceBean.getName().equals(targetBean.getName())) {
//							toolTestResults.add(new ToolTestResult(TestResult.FAIL, operation, session, task, "mismatch in result dataset names, "
//									+ "expecting: " + sourceBean.getName() + " got: " + targetBean.getName()));
//							return;
//						}
					} 
					// Not enough results
					else {
						toolTestResults.add(new ToolTestResult(TestResult.FAIL, operation, session, task, "not enough result datasets"));
						return;
					}
				}
				if (targetIterator.hasNext()) {
					toolTestResults.add(new ToolTestResult(TestResult.FAIL, operation, session, task, "too many result datasets"));
					return;
				}

				// Find and replace metadata 
				targetIterator = task.outputs().iterator();
				for (DataBean sourceBean : outputMap.get(operationRecord)) {
					DataBean targetBean = targetIterator.next();

					// replace metadata contents from the source session
					if (Session.getSession().getPrimaryModule().isMetadata(targetBean)) {
						System.out.println("copying metadata for: " + targetBean.getName());
						OutputStream metadataOut = manager.getContentOutputStreamAndLockDataBean(targetBean);
						InputStream sourceIn = null;
						try {
							sourceIn = sourceManager.getContentStream(sourceBean, DataNotAvailableHandling.EXCEPTION_ON_NA);
							IOUtils.copy(sourceIn, metadataOut);
						} finally {
							IOUtils.closeIfPossible(sourceIn);
							manager.closeContentOutputStreamAndUnlockDataBean(targetBean, metadataOut);
						}
					}
				}

				// Compare data beans, add source bean -> target bean mapping
				List<String> outputsWithMisMatchingSizes = new LinkedList<String>();
				List<String> outputsWithMisMatchingContents = new LinkedList<String>();

				targetIterator = task.outputs().iterator();
				for (DataBean sourceBean : outputMap.get(operationRecord)) {
					DataBean targetBean = targetIterator.next();
					try {
						compareDataBeans(sourceBean, targetBean);
					} catch (Throwable t) {
						toolTestResults.add(new ToolTestResult(TestResult.FAIL, operation, session, task, t.getMessage()));
						return;
					}
					
					// Add source bean -> target bean mapping, needed for further operations
					sourceDataBeanToTargetDataBean.put(sourceBean, targetBean);
					
					// Collect size matches
					if (Session.getSession().getDataManager().getContentLength(sourceBean) != Session.getSession().getDataManager().getContentLength(targetBean)) {
						if (sourceBean.getName().equals(targetBean.getName())) {
							outputsWithMisMatchingSizes.add(sourceBean.getName());
						} else {
							outputsWithMisMatchingSizes.add(sourceBean.getName() + " | " + targetBean.getName());
						}
					}

					// Collect content matches
					if (CHECK_CONTENTS) {
						InputStream sourceIn = null, targetIn = null;
						try {
							sourceIn = sourceManager.getContentStream(sourceBean, DataNotAvailableHandling.EXCEPTION_ON_NA);
							targetIn = manager.getContentStream(targetBean, DataNotAvailableHandling.EXCEPTION_ON_NA);
							if (!IOUtils.contentEquals(sourceIn, targetIn)) {

								if (sourceBean.getName().equals(targetBean.getName())) {
									outputsWithMisMatchingContents.add(sourceBean.getName());
								} else {
									outputsWithMisMatchingContents.add(sourceBean.getName() + " | " + targetBean.getName());
								}
							}
						} finally {
							IOUtils.closeIfPossible(sourceIn);
							IOUtils.closeIfPossible(targetIn);
						}
					}
				}

				// Add result
				ToolTestResult toolTestResult = new ToolTestResult(TestResult.OK, operation, session, task, null);
				toolTestResult.setOutputsWithMisMatchingSizes(outputsWithMisMatchingSizes);
				toolTestResult.setOutputsWithMisMatchingContents(outputsWithMisMatchingContents);
				toolTestResults.add(toolTestResult);

			} finally {
				// Set session data manager back to null to avoid problems
				Session.getSession().setDataManager(null);
			}
		}
	}


	private OperationDefinition getOperationDefinition(String toolId, LinkedList<ToolModule> toolModules) {
		for (ToolModule module : toolModules) {
			OperationDefinition tool = module.getOperationDefinition(toolId);
			if (tool != null) {
				return tool;
			}
		}
		return null;
	}

	/**
	 * 
	 * @param bean1
	 * @param bean2
	 * 
	 * @throws Errors if not equal
	 */
	private void compareDataBeans(DataBean bean1, DataBean bean2) {

//		// Check name, may be already checked though
//		Assert.assertEquals(bean1.getName(), bean2.getName());

		// check content types
		Assert.assertEquals(bean1.getContentType().getType(), bean2.getContentType().getType());
		
		// exact size
		if (FAIL_ON_OUTPUT_SIZE_MISMATCH) {
			Assert.assertEquals("comparing output size", Session.getSession().getDataManager().getContentLength(bean1), Session.getSession().getDataManager().getContentLength(bean2));
		}

		// zero size not allowed if source non-zero
		if (Session.getSession().getDataManager().getContentLength(bean1) > 0) {
			Assert.assertTrue("zero size dataset", Session.getSession().getDataManager().getContentLength(bean2) > 0);
		}
	}

	
	public static void main(String[] args) throws Exception {
		
		boolean testOK = false;

		// needed if things fail early
		webDir = new File(DEFAULT_WEB_DIR);
		
		try {

			String configURL = null;
			String username = null;
			String password = null;
			String sessionsDirName = DEFAULT_SESSIONS_DIR;
			switch(args.length) {
			case 3:
				configURL = args[0];
				username = args[1];
				password = args[2];
				DirectoryLayout.initialiseClientLayout(configURL);
				break;
			case 4:
				configURL = args[0];
				username = args[1];
				password = args[2];
				DirectoryLayout.initialiseClientLayout(configURL);
				sessionsDirName = args[3];
				break;
			case 5:
				configURL = args[0];
				username = args[1];
				password = args[2];
				DirectoryLayout.initialiseClientLayout(configURL);
				sessionsDirName = args[3];
				webDir = new File(args[4]);
				break;

			default:
				System.out.println("Usage: " + SessionReplayTest.class.getSimpleName() + " <config-url username password> <sessions dir> <web dir>\n" +
				"If there are no arguments, config file is used.");
				updateFlagFileAndExit(false);
			}


			// initialize
			SessionReplayTest test = null;
			try {
				test = new SessionReplayTest(username, password, configURL);
			} catch (Exception e) {
				e.printStackTrace();
				System.out.println("TOOL TESTS INIT ERROR");
				updateFlagFileAndExit(false);
			}

			// run
			try {
				test.setUp();
				testOK = test.testSessions(sessionsDirName);
			} catch (Exception e) {
				e.printStackTrace();
				System.out.println("TOOL TEST ERROR");
				test.tearDown();
				updateFlagFileAndExit(false);
			} finally {
				test.tearDown();
			}

			if (testOK) {
				System.out.println("TOOL TESTS OK");
				updateFlagFileAndExit(true);
			} else {
				System.out.println("TOOL TESTS FAILED");
				updateFlagFileAndExit(false);
			}
		} 
		
		// Should only get here if something weird happens
		finally {
			updateFlagFileAndExit(false);
		}
	}
	
	private void createReports() throws IOException {
		
		
		// count unique tools and sessions
		final HashMap<String, Integer> uniqueTools = new HashMap<String,Integer>(); 
		HashMap<String, Integer> failCounts = new HashMap<String,Integer>(); 
		
		Set<File> uniqueSessions = new HashSet<File>();
		HashMap<String, List<File>> toolToSessionsMap = new HashMap<String, List<File>>();
		for (ToolTestResult toolTestResult : toolTestResults) {
			if (!uniqueTools.containsKey(toolTestResult.getOperation().getID())) {
				uniqueTools.put(toolTestResult.getOperation().getID(), 1);
			} else {
				uniqueTools.put(toolTestResult.getOperation().getID(), uniqueTools.get(toolTestResult.getOperation().getID()) + 1);
			}

			if (!toolToSessionsMap.containsKey(toolTestResult.getOperation().getID())) {
				List<File> sessionsList = new LinkedList<File>();
				sessionsList.add(toolTestResult.getSession());
				toolToSessionsMap.put(toolTestResult.getOperation().getID(), sessionsList);
			} else {
				List<File> sessionsList = toolToSessionsMap.get(toolTestResult.getOperation().getID());
				if (!sessionsList.contains(toolTestResult.getSession())) {
					sessionsList.add(toolTestResult.getSession());
				}
			}
			
			if (!uniqueSessions.contains(toolTestResult.getSession())) {
				uniqueSessions.add(toolTestResult.getSession());
			}
		}

		// sort tools by test count
		String[] toolsSortedbyTestCount = uniqueTools.keySet().toArray(new String[]{});
		Arrays.sort(toolsSortedbyTestCount, new Comparator<String>() {
			@Override
			public int compare(String arg0, String arg1) {
				return uniqueTools.get(arg1) - uniqueTools.get(arg0);
			}
		});
		
		// failed and successful tasks
		List<ToolTestResult> failedTasks = new LinkedList<ToolTestResult>();
		List<ToolTestResult> successTasks = new LinkedList<ToolTestResult>();
		for (ToolTestResult toolTestResult : toolTestResults) {
			if (TestResult.FAIL.equals(toolTestResult.getTestResult())) {
				failedTasks.add(toolTestResult);
				String toolID = toolTestResult.getOperation().getID();
				if (failCounts.containsKey(toolID)) {
					failCounts.put(toolID, failCounts.get(toolID) + 1);
				} else {
					failCounts.put(toolID, 1);
				}
			} else {
				successTasks.add(toolTestResult);
			}
		}
		

		// create files
		webDir.mkdirs();
		File screenOutputsDir = new File(webDir, SCREEN_OUTPUTS_DIR);

		// delete existing screen outputs
		if (screenOutputsDir.exists()) {
			for (File f : screenOutputsDir.listFiles()) {
				f.delete();
			}
		}
		
		File htmlFile = new File(webDir,"index.html");
		
		FileWriter writer = new FileWriter(htmlFile);
		writer.write("<html>");
		writer.write("<head>" + CSS + "</head>");
		
		writer.write("<body>");
		
		// Main title
		String titleStatus = "<span style=\"color: green\">everything ok!</span>";
		if (failedTasks.size() > 0 || sessionsWithErrors.size() > 0 || sessionsWithMissingTools.size() > 0) {
			titleStatus = "<span style=\"color: red\">" + 
						failCounts.keySet().size() + " tool(s) failed in " + failedTasks.size() + " test(s), " +
						sessionsWithErrors.size() + " session(s) with errors, " +
						sessionsWithMissingTools.size() + " sessions(s) with missing tools" +
						"</span>";
		}
		writer.write("<h2>Tool tests &ndash; " + titleStatus + "</h2>");

		writer.write("<h3>Summary</h3>");

		// Summary
		long duration = (System.currentTimeMillis() - startTime.getTime())/1000;
		String totalTime = String.format("%02dm %02ds", (duration/60), (duration%60));
		writer.write("<table>" +
				"<tr><td>Results summary</td><td>" + 
				 successTasks.size() + " <span" + (successTasks.isEmpty() ? "" : " style=\"color: green\"") + ">ok</span>, " + 
				 failedTasks.size() + " <span" + (failedTasks.isEmpty() ? "" : " style=\"color: red\"") + ">failed</span>, "+
				 toolTestResults.size() + " total</td</tr>" +
				
				 "<tr><td>Tool coverage</td><td>" + uniqueTools.size() + "/" + getTotalNumberOfTools() + "</td></tr>" +
				
				 "<tr><td>Sessions</td>" + 
				 "<td>" + uniqueSessions.size() + " total, " + 
				 sessionsWithErrors.size() + " <span" + (sessionsWithErrors.isEmpty() ? "" : " style=\"color: red\"") + ">with errors</span>, " +
				 sessionsWithMissingTools.size() + " <span" + (sessionsWithMissingTools.isEmpty() ? "" : " style=\"color: red\"") + ">with missing tools</span>, "+
				 "</td></tr>" +

				 
				 "<tr><td>Start time</td><td>" + startTime.toString() + "</td></tr>" +
				 "<tr><td>Total time</td><td>" + totalTime + "</td></tr>" +

				"</table>");
		
		// Sessions with errors
		if (sessionsWithErrors.size() > 0) {
			writer.write("<h3>Sessions with errors</h3>");
			writer.write("<table><tr>" + 
					"<th>Session</th>" + 
					"<th>Throwable</th>" +
					"</tr>");
			for (Entry<File, Throwable> entry : sessionsWithErrors.entrySet()) {
				writer.write("<tr>" +
						"<td>" + entry.getKey().getName() + "</td>" +
						"<td>" + entry.getValue().toString() + "</td>" +
						"</tr>");
			}
			writer.write("</table>");
		}

		// Missing tools
		if (sessionsWithMissingTools.size() > 0) {
			writer.write("<h3>Sessions with missing tools</h3>");
			writer.write("<table><tr>" + 
					"<th>Session</th>" + 
					"<th>Tool</th>" +
					"</tr>");
			for (Entry<File, String> entry : sessionsWithMissingTools.entrySet()) {
				writer.write("<tr>" +
						"<td>" + entry.getKey().getName() + "</td>" +
						"<td>" + entry.getValue() + "</td>" +
						"</tr>");
			}
			writer.write("</table>");
		}

		
		
		// Tool test results
		writer.write("<h3>Tool test results</h3>");
		writer.write("<table><tr>" + 
				"<th>Tool</th>" + 
				"<th>Result</th>" +
				"<th>Session</th>" +
				"<th>Task state</th>" + 
				"<th>Test error message</th>" + 
				"<th>Task error message</th>" +
				"<th>Task screen output</th>" + 
				"<th>Duration</th>" + 
				"<th>Outputs with mismatching sizes</th>" + 
				"<th>Outputs with mismatching contents</th>" + 
				"</tr>");

		// Failed tests
		for (ToolTestResult toolTestResult : failedTasks) {
			writer.write("<tr>" +
					"<td>" + toolTestResult.getOperation().getDefinition().getFullName() + "</td>" +
					"<td style=\"color: red\">" + toolTestResult.getTestResult() + "</td>" +
					"<td>" + createSessionLink(toolTestResult.getSession()) + "</td>" + 
					"<td>" + toolTestResult.getTask().getState() + "</td>" +
					"<td>" + nullToEmpty(toolTestResult.getTestErrorMessage()) + "</td>" +
					"<td>" + nullToEmpty(toolTestResult.getTask().getErrorMessage()) + "</td>" +
					"<td>" + createScreenOutputLink(toolTestResult.getTask()) + "</td>" +
					"<td><nobr>" + getDurationString(toolTestResult.getTask()) + "</nobr></td>" +
					"<td>" + getOutputsWithMisMatchingSizes(toolTestResult) + "</td>" +
					"<td>" + getOutputsWithMisMatchingContents(toolTestResult) + "</td>" +
					"</tr>");
		}

		// Successful tests
		for (ToolTestResult toolTestResult : successTasks) {
			writer.write("<tr>" +
					"<td>" + toolTestResult.getOperation().getDefinition().getFullName() + "</td>" +
					"<td>" + toolTestResult.getTestResult() + "</td>" +
					"<td>" + createSessionLink(toolTestResult.getSession()) + "</td>" + 
					"<td>" + toolTestResult.getTask().getState() + "</td>" +
					"<td>" + nullToEmpty(toolTestResult.getTestErrorMessage()) + "</td>" +
					"<td>" + nullToEmpty(toolTestResult.getTask().getErrorMessage()) + "</td>" +
					"<td>" + createScreenOutputLink(toolTestResult.getTask()) + "</td>" +
					"<td><nobr>" + getDurationString(toolTestResult.getTask()) + "</nobr></td>" +
					"<td>" + getOutputsWithMisMatchingSizes(toolTestResult) + "</td>" +
					"<td>" + getOutputsWithMisMatchingContents(toolTestResult) + "</td>" +
					"</tr>");
		}
		writer.write("</table>");
		

		// Coverage
		writer.write("<h3>Coverage</h3>");
		writer.write("<table><tr>" + 
				"<th>Tool</th>" + 
				"<th>Count</th>" +
				"<th>Sessions</th>" + 
				"</tr>");

		// Tools with tests
		for (String toolID : toolsSortedbyTestCount) {
			String sessionsString = "";
			for (File session : toolToSessionsMap.get(toolID)) {
				sessionsString += createSessionLink(session) + " ";
			}
			sessionsString.trim();
			
			writer.write("<tr>" +
					"<td>" + this.getOperationDefinition(toolID, toolModules).getFullName()  + "</td>" +
					"<td>" + uniqueTools.get(toolID) + "</td>" +
					"<td>" + sessionsString + "</td>" +
			    	"</tr>");
		}

		// Tools without tests
		for (OperationDefinition od : getAllOperationDefinitions()) {
			if (!uniqueTools.containsKey(od.getID())) {
			writer.write("<tr>" +
					"<td>" + od.getFullName()  + "</td>" +
					"<td>" + 0 + "</td>" +
					"<td>" + "" + "</td>" +
			    	"</tr>");
			}
		}
		
		
		
		writer.write("</table>");

		
		writer.write("</body></html>");
		writer.flush();
		writer.close();
	}
	
	
	private String getOutputsWithMisMatchingSizes(ToolTestResult toolTestResult) {
		String s = "";
		for (String output : toolTestResult.getOutputsWithMisMatchingSizes()) {
			s += output + ", ";
		}
		if (s.endsWith(", ")) {
			s = s.substring(0, s.length() - ", ".length());
		}
		return s;
	}

	private String getOutputsWithMisMatchingContents(ToolTestResult toolTestResult) {
		String s = "";
		for (String output : toolTestResult.getOutputsWithMisMatchingContents()) {
			s += output + ", ";
		}
		if (s.endsWith(", ")) {
			s = s.substring(0, s.length() - ", ".length());
		}
		return s;
	}

	
	private static void updateFlagFileAndExit(boolean testOK) throws IOException {
		File flagFile = new File(webDir, FLAG_FILE);
		if (testOK) {
			if (!flagFile.exists()) {
				flagFile.createNewFile();
			} else {
				flagFile.setLastModified(System.currentTimeMillis());
			}
		} else {
			flagFile.delete();
		}
		
		if (testOK) {
			System.exit(0);
		} else {
			System.exit(1);
		}
	}
	
	
	private enum TestResult {
		OK,FAIL;
	}
	
	private class ToolTestResult {
		private TestResult testResult;
		private Operation operation;
		private File sessionFile;
		private Task task;
		private String testErrorMessage;
		private List<String> outputsWithMisMatchingSizes = new LinkedList<String>();
		private List<String> outputsWithMisMatchingContents = new LinkedList<String>();

		
		public ToolTestResult(TestResult testResult, Operation operation, File sessionFile, Task task, String testErrorMessage) {
			this.testResult = testResult;
			this.operation = operation;
			this.sessionFile = sessionFile;
			this.task = task;
			this.testErrorMessage = testErrorMessage;
		}

		public TestResult getTestResult() {
			return testResult;
		}
		public Operation getOperation() {
			return operation;
		}
		public File getSession() {
			return sessionFile;
		}
		public Task getTask() {
			return task;
		}
		
		public String getTestErrorMessage() {
			return testErrorMessage;
		}

		public List<String> getOutputsWithMisMatchingSizes() {
			return outputsWithMisMatchingSizes;
		}

		public void setOutputsWithMisMatchingSizes(List<String> outputs) {
			this.outputsWithMisMatchingSizes = outputs;
		}

		public List<String> getOutputsWithMisMatchingContents() {
			return outputsWithMisMatchingContents;
		}

		public void setOutputsWithMisMatchingContents(List<String> outputs) {
			this.outputsWithMisMatchingContents = outputs;
		}

		
	}
	
	private int getTotalNumberOfTools() {
		Set<String> uniqueIDs = new HashSet<String>();
		for (ToolModule toolModule : toolModules) {
			for (ToolCategory toolCategory : toolModule.getVisibleCategories()) {
				for (OperationDefinition od : toolCategory.getToolList()) {
					if (!uniqueIDs.contains(od.getID())) {
						uniqueIDs.add(od.getID());
					}
				}
			}
		}
		return uniqueIDs.size();
	}

	private List<OperationDefinition> getAllOperationDefinitions() {
		List<OperationDefinition> tools = new LinkedList<OperationDefinition>();
		for (ToolModule toolModule : toolModules) {
			for (ToolCategory toolCategory : toolModule.getVisibleCategories()) {
				for (OperationDefinition od : toolCategory.getToolList()) {
					tools.add(od);
				}
			}
		}
		return tools;
	}

	private String nullToEmpty(String s) {
		if (s == null) {
			return "";
		} else {
			return s;
		}
	}
	
	private String createSessionLink(File sessionFile) {
		return "<a href=\"" + sessionsDir + "/" + sessionFile.getName() + "\">" + sessionFile.getName() + "</a>";
	}
	
	
	private String createScreenOutputLink(Task task) {
		if (task.getScreenOutput() == null) {
			return "";
		}
		
		File outputsDir = new File(webDir, SCREEN_OUTPUTS_DIR);
		outputsDir.mkdirs();
		File outputFile = new File(outputsDir, task.getId() + "-output");
		try {
			IOUtils.copy(new ByteArrayInputStream(task.getScreenOutput().getBytes()), outputFile);
		} catch (Exception e) {
			e.printStackTrace();
			return "creating screen output failed";
		}
		
		return "<a href=\"" + SCREEN_OUTPUTS_DIR + "/" + outputFile.getName() + "\">" + "output" + "</a>";
	}

	private String getDurationString(Task task) {
		long duration = task.getExecutionTime() / 1000;
		return String.format("%02dm %02ds", (duration/60), (duration%60));
	}
	
	
	public static class SessionLoadingSkeletonApplication extends ClientApplication {

		private SessionReplayTest parent;

		public SessionLoadingSkeletonApplication(SessionReplayTest parent, LinkedList<ToolModule> toolModules, DataManager manager) {
			this.parent = parent;
			this.toolModules = toolModules;
			this.manager = manager;
			logger = org.apache.log4j.Logger.getLogger(SessionLoadingSkeletonApplication.class);
		}
		
		@Override
		public OperationDefinition getOperationDefinition(String toolId) {
			return parent.getOperationDefinition(toolId, toolModules);
		};

		@Override
		public void initialiseGUIThreadSafely(File session) throws MicroarrayException, IOException {
			throw new UnsupportedOperationException("not supported by skeleton app");
		}

		@Override
		public void reportException(Exception e) {
			throw new UnsupportedOperationException("not supported by skeleton app");
		}

		@Override
		public void reportInitialisationThreadSafely(String report, boolean newline) {
			throw new UnsupportedOperationException("not supported by skeleton app");
		}

		@Override
		public void reportTaskError(Task job) throws MicroarrayException {
			throw new UnsupportedOperationException("not supported by skeleton app");
		}

		@Override
		public void runBlockingTask(String taskName, Runnable runnable) {
			throw new UnsupportedOperationException("not supported by skeleton app");
		}

		@Override
		public void showDialog(String title, String message, String details, Severity severity, boolean modal) {
			showDialog(title, message, details, severity, modal, DetailsVisibility.DETAILS_VISIBLE, null);
		}

		@Override
		public void showDialog(String title, String message, String details, Severity severity, boolean modal, DetailsVisibility detailsVisibility, PluginButton button) {
			showDialog(title, message, details, severity, modal, detailsVisibility, button, false);
		}

		@Override
		public void showDialog(String title, String message, String details, Severity severity, boolean modal, DetailsVisibility detailsVisibility, PluginButton button, boolean feedBackEnabled) {
			System.out.println(title + "\n" + message + "\n" + details);
		}

		@Override
		public void reportExceptionThreadSafely(Exception e) {
			throw new UnsupportedOperationException("not supported by skeleton app");
		}
	}
}

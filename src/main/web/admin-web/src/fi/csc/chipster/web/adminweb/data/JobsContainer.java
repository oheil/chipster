package fi.csc.chipster.web.adminweb.data;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.locks.Lock;

import javax.jms.JMSException;

import org.apache.log4j.Logger;

import com.vaadin.data.util.BeanItemContainer;

import fi.csc.chipster.web.adminweb.ui.JobsView;
import fi.csc.microarray.config.ConfigurationLoader.IllegalConfigurationException;
import fi.csc.microarray.exception.MicroarrayException;
import fi.csc.microarray.messaging.admin.CompAdminAPI;
import fi.csc.microarray.messaging.admin.JobsEntry;
import fi.csc.microarray.messaging.admin.CompAdminAPI.JobsListener;

public class JobsContainer extends BeanItemContainer<JobsEntry> implements Serializable, JobsListener {
	
	private static final Logger logger = Logger.getLogger(JobsContainer.class);

	public static final String USERNAME = "username";
	public static final String OPERATION = "operation";
	public static final String STATUS = "status";
	public static final String COMPHOST = "compHost";
	public static final String START_TIME = "startTime";
	public static final String CANCEL_LINK = "cancelLink";

	public static final Object[] NATURAL_COL_ORDER  = new String[] {
		USERNAME, 		OPERATION, 		STATUS, 	COMPHOST, 		START_TIME, 	CANCEL_LINK };

	public static final String[] COL_HEADERS_ENGLISH = new String[] {
		"Username", 	"Operation", 	"Status", 	"Comp host", 	"Start time", 	"" };
	
	private CompAdminAPI compAdminAPI;

	private JobsView view;



	public JobsContainer(JobsView view, CompAdminAPI compAdminAPI) throws IOException, IllegalConfigurationException, MicroarrayException, JMSException {
		super(JobsEntry.class);
		this.view = view;
		this.compAdminAPI = compAdminAPI;
	}
	
	public void update() {		
		
		try {
			compAdminAPI.queryRunningJobs(this);
			// clear table even if there aren't any status updates
			statusUpdated(new ArrayList<JobsEntry>());
			
		} catch (JMSException | InterruptedException e) {
			logger.error(e);
		}
	}

	@Override
	public void statusUpdated(Collection<JobsEntry> jobs) {
		
		//Following is null if data loading was faster than UI initialisation in another thread
		if (view.getEntryTable().getUI() != null) {
			Lock tableLock = view.getEntryTable().getUI().getSession().getLockInstance();
			tableLock.lock();
			try {
				removeAllItems();

				for (JobsEntry entry : jobs) {
					addBean(entry);
				}

			} finally {
				tableLock.unlock();
			}
		}
	}
}
package fi.csc.chipster.web.adminweb.ui;

import java.io.IOException;

import javax.jms.JMSException;

import org.apache.log4j.Logger;

import com.vaadin.data.Property;
import com.vaadin.data.Property.ValueChangeEvent;
import com.vaadin.data.Property.ValueChangeListener;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;

import fi.csc.chipster.web.adminweb.ChipsterAdminUI;
import fi.csc.chipster.web.adminweb.data.JobsContainer;
import fi.csc.chipster.web.adminweb.util.Notificationutil;
import fi.csc.microarray.config.ConfigurationLoader.IllegalConfigurationException;
import fi.csc.microarray.exception.MicroarrayException;
import fi.csc.microarray.messaging.admin.CompAdminAPI;
import fi.csc.microarray.messaging.admin.JobsEntry;

public class JobsView extends AsynchronousView implements ClickListener, ValueChangeListener {
	
	private static final Logger logger = Logger.getLogger(JobsView.class);
	
	public static final int WAIT_SECONDS = 1;
	
	private HorizontalLayout toolbarLayout;

	private JobsTable table;
	private CompAdminAPI compAdminAPI;
	private JobsContainer dataSource;

	private ChipsterAdminUI app;

	public JobsView(ChipsterAdminUI app) {
		
		super(WAIT_SECONDS);
		
		this.app = app;
		try {
			compAdminAPI = new CompAdminAPI();
			dataSource = new JobsContainer(this, compAdminAPI);

			table = new JobsTable(this);
			table.setContainerDataSource(dataSource);

			table.setVisibleColumns(JobsContainer.NATURAL_COL_ORDER);
			table.setColumnHeaders(JobsContainer.COL_HEADERS_ENGLISH);

			this.addComponent(getToolbar());
			this.addComponent(super.getProggressIndicator());
			this.addComponent(table);

			setSizeFull();
			this.setExpandRatio(table, 1);

		} catch (IOException | IllegalConfigurationException | MicroarrayException | JMSException e) {
			logger.error("can't initialize jobs view", e);
		} 
	}

	public HorizontalLayout getToolbar() {

		if (toolbarLayout == null) {
			
			toolbarLayout = new HorizontalLayout();
			
			toolbarLayout.addComponent(super.createRefreshButton(this));
			
			Label spaceEater = new Label(" ");
			toolbarLayout.addComponent(spaceEater);
			toolbarLayout.setExpandRatio(spaceEater, 1);
			
			toolbarLayout.addComponent(app.getTitle());	
			
			toolbarLayout.setWidth("100%");
			toolbarLayout.setStyleName("toolbar");
		}

		return toolbarLayout;
	}

	public void buttonClick(ClickEvent event) {
		final Button source = event.getButton();

		if (super.isRefreshButton(source)) {
			update();
		} 
	}
	
	public void update() {
		
		super.submitUpdateAndWait(new Runnable() {

			@Override
			public void run() {				
				dataSource.update();				
			}			
		});				
	}

	public void valueChange(ValueChangeEvent event) {
		Property<?> property = event.getProperty();
		if (property == table) {
			//			Item item = personList.getItem(personList.getValue());
			//			if (item != personForm.getItemDataSource()) {
			//				personForm.setItemDataSource(item);
			//			}
		}
	}

	public void cancel(JobsEntry job) {
		try {
			compAdminAPI.cancelJob(job.getJobId());
		} catch (MicroarrayException e) {
			Notificationutil.showFailNotification(e.getClass().getSimpleName(), e.getMessage());
		}
		update();
	}

	public JobsTable getEntryTable() {
		return table;
	}
}

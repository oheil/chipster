package fi.csc.microarray.client.visualisation.methods.gbrowser.gui;

import java.io.IOException;

import javax.swing.SwingUtilities;

import fi.csc.microarray.client.visualisation.methods.gbrowser.fileIndex.EnsemblRestApiClient;
import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.GeneIndexActions.GeneLocationListener;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Region;
import fi.csc.microarray.client.visualisation.methods.gbrowser.util.GBrowserException;

public class EnsemblRestGeneSearch implements GeneSearchProvider {
	
	private DataUrl server;
	private String species;

	public EnsemblRestGeneSearch(DataUrl server, String species) {
		this.server = server;
		this.species = species;
	}

	@Override
	public void requestLocation(final String geneName,
			final GeneLocationListener geneLocationListener) {
		
		new Runnable () {
			public void run() {
				Region region = null;
				try {
					region = EnsemblRestApiClient.getLocationOfGene(server, species, geneName);
				} catch (GBrowserException | IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				reportRegion(region, geneLocationListener);
			}

		}.run();
	}

	private void reportRegion(final Region region, final GeneLocationListener geneLocationListener) {
		SwingUtilities.invokeLater(new Runnable() {
			
			@Override
			public void run() {
				geneLocationListener.geneLocation(region);
			}
		});	
	}

	@Override
	public void initializeDataResultListeners() {
		//nothing to do because this class gets its all its data from the Rest API
	}
}

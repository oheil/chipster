package fi.csc.microarray.client.visualisation.methods.gbrowser.gui;

import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.GeneIndexActions.GeneLocationListener;

public interface GeneSearchProvider {

	void requestLocation(String gene, GeneLocationListener geneLocationListener);

	void initializeDataResultListeners();

}

package fi.csc.microarray.client.visualisation.methods.gbrowser.fileIndex;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import fi.csc.microarray.client.visualisation.methods.gbrowser.GBrowser;
import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.DataUrl;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataRequest;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataResult;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataType;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Feature;
import fi.csc.microarray.client.visualisation.methods.gbrowser.runtimeIndex.DataSource;
import fi.csc.microarray.client.visualisation.methods.gbrowser.runtimeIndex.DataThread;
import fi.csc.microarray.client.visualisation.methods.gbrowser.util.GBrowserException;

public class EnsemblRestReferenceConversion extends DataThread {

	private String species;
	private DataUrl server;

	public EnsemblRestReferenceConversion(DataUrl server, String species, final GBrowser browser) throws URISyntaxException, IOException {

		super(browser, null);

		DataSource dataSource = new DataSource(server);
		super.setDataSource(dataSource);
		
		this.server = server;
		this.species = species;
	}

	@Override
	protected void processDataRequest(DataRequest request) {

		List<Feature> responseList = new LinkedList<Feature>();
		
		try {
			Map<String, String> map = EnsemblRestApiClient.getReferenceSequence(server, species, request);
					
			String sequence = map.get("seq");

			LinkedHashMap<DataType, Object> values = new LinkedHashMap<DataType, Object>();
			values.put(DataType.SEQUENCE, sequence);

			Feature feature = new Feature(request, values);

			/*
			 * NOTE! RegionContents created from the same read area has to be equal in methods equals, hash and compareTo. Primary types
			 * should be ok, but objects (including tables) has to be handled in those methods separately. Otherwise tracks keep adding
			 * the same reads to their read sets again and again.
			 */
			responseList.add(feature);

			// Send result
			createDataResult(new DataResult(request.getStatus(), responseList));				
		} catch (GBrowserException | IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public String toString() {
		return this.getClass().getName() + " - " + server;
	}
}

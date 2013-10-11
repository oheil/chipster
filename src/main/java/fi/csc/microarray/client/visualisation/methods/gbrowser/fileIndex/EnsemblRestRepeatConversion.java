package fi.csc.microarray.client.visualisation.methods.gbrowser.fileIndex;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import fi.csc.microarray.client.visualisation.methods.gbrowser.GBrowser;
import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.DataUrl;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataRequest;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataResult;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Feature;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Region;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Strand;
import fi.csc.microarray.client.visualisation.methods.gbrowser.runtimeIndex.DataSource;
import fi.csc.microarray.client.visualisation.methods.gbrowser.runtimeIndex.DataThread;
import fi.csc.microarray.client.visualisation.methods.gbrowser.util.GBrowserException;

public class EnsemblRestRepeatConversion extends DataThread {

	private String species;
	private DataUrl server;

	public EnsemblRestRepeatConversion(DataUrl server, String species, final GBrowser browser) throws URISyntaxException, IOException {

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
			List<Map<String, String>> maps = EnsemblRestApiClient.getRepeats(server, species, request);
					
			for (Map<String, String> map : maps) {
				Region region = EnsemblRestApiClient.getRegion(map);			List<Feature> resultList = new LinkedList<Feature>();

				//TODO Create selectable object for repeats and include following information
				Strand strand = EnsemblRestApiClient.getStrand(map);
				String description = map.get("description");

				responseList.add(new Feature(region));
			}			
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

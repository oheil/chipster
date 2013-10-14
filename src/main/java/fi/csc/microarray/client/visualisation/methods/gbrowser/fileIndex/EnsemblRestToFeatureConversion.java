package fi.csc.microarray.client.visualisation.methods.gbrowser.fileIndex;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import fi.csc.microarray.client.visualisation.methods.gbrowser.GBrowser;
import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.DataUrl;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataRequest;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataResult;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataType;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Exon;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Feature;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Gene;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.GeneRequest;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.GeneResult;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Region;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Strand;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Transcript;
import fi.csc.microarray.client.visualisation.methods.gbrowser.runtimeIndex.DataSource;
import fi.csc.microarray.client.visualisation.methods.gbrowser.runtimeIndex.DataThread;

public class EnsemblRestToFeatureConversion extends DataThread {
		
	private DataUrl server;
	private String species;


	public EnsemblRestToFeatureConversion(DataUrl server, String species, final GBrowser browser) throws URISyntaxException, IOException {
	    
		super(browser, null);	
		this.server = server;
		this.species = species;
		DataSource dataSource = new DataSource(server);
		super.setDataSource(dataSource);
	}

	@Override
	protected void processDataRequest(DataRequest request) {				

		if (request instanceof GeneRequest) {

			throw new UnsupportedOperationException(getClass().getSimpleName() + " doesn't support Gene search queries. Use class EnsemblRestApiClient instead.");

		} else { 

			long start = request.start.bp;
			long end = request.end.bp;

			//Extend area to be able to draw introns at screen edge, but don't below 1
			//TODO Be more clever to avoid getting so much useless data
			start = Math.max((long)start - GtfToFeatureConversion.MAX_INTRON_LENGTH, 1);
			end = end + GtfToFeatureConversion.MAX_INTRON_LENGTH;

			Region requestRegion = new Region(start, end, request.start.chr);

			final long CHUNK_SIZE = 1*1000*1000;

			for (long chunkStart = requestRegion.start.bp; chunkStart <= requestRegion.end.bp; chunkStart += CHUNK_SIZE) {
				Region chunkRegion = new Region(chunkStart, Math.min(chunkStart + CHUNK_SIZE, requestRegion.end.bp), requestRegion.start.chr);

				processDataRequestChunk(request, chunkRegion);
			}
		}
	}
	
	protected void processDataRequestChunk(DataRequest request, Region chunkRegion) {
				
		List<Feature> resultList = new LinkedList<Feature>();
		List<Exon> exons;
		
		try {
			exons = fetchExons(request, chunkRegion);

			for (Exon exon : exons) {
				
				LinkedHashMap<DataType, Object> valueMap = new LinkedHashMap<DataType, Object>();
				
				valueMap.put(DataType.VALUE, exon);
				
				Feature feature = new Feature(exon.getRegion(), valueMap);
				
				resultList.add(feature);

			}
			super.createDataResult(new DataResult(request.getStatus(), resultList));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}						
	}

	private LinkedList<Exon> fetchExons(DataRequest request, Region chunkRegion) throws Exception {
		
		LinkedList<Exon> exons = new LinkedList<Exon>();
		
		List<Map<String, String>> jsonMaps = EnsemblRestApiClient.getAnnotations(this.server, this.species, request);
			    	    
	    Map<String, Gene> geneMap = new HashMap<>();
	    Map<String, Transcript> transcriptMap = new HashMap<>();

	    //Get genes and transcripts
	    for (Map<String, String> map : jsonMaps) {

	    	String featureType = map.get("feature_type");
	    	String externalName = map.get("external_name");
	    	String id = map.get("ID");


	    	switch (featureType) {
	    	case "gene":

	    		String biotype = map.get("biotype");
	    		Gene gene = new Gene(externalName, biotype, id);
	    		geneMap.put(id, gene);

	    		break;
	    	case "transcript":

	    		String parentId = map.get("Parent");
	    		Gene geneId = new Gene(null, null, parentId);
	    		Transcript transcript = new Transcript(externalName, geneId, id);
	    		transcriptMap.put(id, transcript);
	    		break;

	    	default:
	    		break;
	    	}
	    }
	    
	    //get exons
	    for (Map<String, String> map : jsonMaps) {

	    	String featureType = map.get("feature_type");
	    	switch (featureType) {
	    	case "exon":
	    	case "cds":

	    		Region region = EnsemblRestApiClient.getRegion(map);
	    		
	    		region.strand = EnsemblRestApiClient.getStrand(map);

	    		String parentId = (String) map.get("Parent");
	    		String exonNumberString = (String) map.get("rank");

	    		int exonNumber = -1;
	    		if (exonNumberString != null) {
	    			exonNumber = new Integer(exonNumberString);
	    		}

	    		Transcript transcript = transcriptMap.get(parentId);
	    		Gene geneIdGene = transcript.getGene();
	    		String geneId = geneIdGene.getId();
	    		Gene gene = geneMap.get(geneId);
	    		//FIXME use transcript biotype instead of gene's
	    		Exon exon = new Exon(region, featureType, exonNumber, gene.getId(), transcript.getId(), gene.getName(), transcript.getName(), gene.getBiotype());
	    		exons.add(exon);

	    		break;

	    	default:
	    		break;
	    	}
	    }
	    
		//IndexKeys are not needed, because gtf contains unique identifiers for lines

		return exons;
	}
}

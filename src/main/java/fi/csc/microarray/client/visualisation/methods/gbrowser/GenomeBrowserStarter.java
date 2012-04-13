package fi.csc.microarray.client.visualisation.methods.gbrowser;

import java.awt.Cursor;
import java.awt.Dimension;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

import org.jfree.chart.JFreeChart;

import fi.csc.microarray.client.visualisation.methods.gbrowser.dataFetcher.SAMHandlerThread;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.RegionDouble;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Chromosome;


/**
 * Quick and dirty starter utility for genome browser development and debugging.
 */
public class GenomeBrowserStarter {

	private static URL BAM_DATA_FILE = null;
	private static URL BAI_DATA_FILE = null;
	private static URL CYTOBAND_FILE = null;
	private static URL CYTOBAND_REGION_FILE = null;
	private static URL CYTOBAND_COORD_SYSTEM_FILE = null;
	private static URL GTF_ANNOTATION_FILE = null;

	private static final String dataPath;
	
	static {
		
		dataPath = System.getProperty("user.home") + "/chipster/ohtu/";

		try {
			BAM_DATA_FILE = new File(dataPath + "ohtu-within-chr.bam").toURI().toURL();
			BAI_DATA_FILE = new File(dataPath + "ohtu-within-chr.bam.bai").toURI().toURL();
			
			/*
			 *  Download and extract following files
			 *  
			 *  For human:
			 *  ftp://ftp.ensembl.org/pub/release-65/mysql/homo_sapiens_core_65_37/karyotype.txt.gz
			 *  ftp://ftp.ensembl.org/pub/release-65/mysql/homo_sapiens_core_65_37/seq_region.txt.gz
			 *  ftp://ftp.ensembl.org/pub/release-65/mysql/homo_sapiens_core_65_37/coord_system.txt.gz
			 *  
			 *  For rat:
			 *  ftp://ftp.ensembl.org/pub/release-65/mysql/rattus_norvegicus_core_65_34/seq_region.txt.gz
			 *  ftp://ftp.ensembl.org/pub/release-65/mysql/rattus_norvegicus_core_65_34/karyotype.txt.gz
			 *  ftp://ftp.ensembl.org/pub/release-65/mysql/rattus_norvegicus_core_65_34/coord_system.txt.gz
			 *  
			 *  and adjust these paths correspondingly:
			 */
			
			CYTOBAND_FILE = new File(dataPath + "Homo_sapiens.GRCh37.65.cytobands.txt").toURI().toURL();
			CYTOBAND_REGION_FILE = new File(dataPath + "Homo_sapiens.GRCh37.65.seq_region.txt").toURI().toURL();
			CYTOBAND_COORD_SYSTEM_FILE = new File(dataPath + "Homo_sapiens.GRCh37.65.coord_system.txt").toURI().toURL();
			
			//ftp://ftp.ensembl.org/pub/release-65/gtf/homo_sapiens/Homo_sapiens.GRCh37.65.gtf.gz
			GTF_ANNOTATION_FILE = new File(dataPath + "Homo_sapiens.GRCh37.65.gtf").toURI().toURL();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
	}
	
	private static void checkData() {
		URL[] urls = new URL[] { BAM_DATA_FILE, BAI_DATA_FILE, CYTOBAND_FILE, CYTOBAND_REGION_FILE, CYTOBAND_COORD_SYSTEM_FILE, GTF_ANNOTATION_FILE };
		boolean fileNotFoundFail = false;
		for (URL url : urls) {
			File file = null;
			
			if (url == null) {
				//Propably intentional
				continue;
			}

			try {
				file = new File(url.toURI());
			} catch (URISyntaxException e) {
				fileNotFoundFail = true;
			} 
			if (!file.exists()) {
				System.err.println("File not found: " + file);
				fileNotFoundFail = true;
			}
		}
		if (fileNotFoundFail) {
			System.exit(1);
		}
	}

	public static void main(String[] args) throws IOException {

		JFrame frame = new JFrame();
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		try {
			getGenomeBrowserPanel();
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}

		frame.add(panel);
		//frame.add(new JButton("Button"));
		frame.setSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
		
		frame.setVisible(true);
	}
	
	private static TooltipAugmentedChartPanel panel;
	private static GenomePlot plot;
	protected static int PREVIEW_WIDTH = 1280;
	protected static int PREVIEW_HEIGHT = 768;
	
	public static TooltipAugmentedChartPanel getGenomeBrowserPanel() throws FileNotFoundException, MalformedURLException, URISyntaxException {
		
		checkData();
		
		boolean horizontal = true;
		
		panel = new TooltipAugmentedChartPanel();
		
		plot = new GenomePlot(panel, horizontal);
		
		TrackFactory.addCytobandTracks(plot, new CytobandDataSource(CYTOBAND_FILE, CYTOBAND_REGION_FILE, CYTOBAND_COORD_SYSTEM_FILE));
		
		TrackFactory.addTitleTrack(plot, "Annotations");		
		TrackFactory.addGeneTracks(plot, new LineDataSource(GTF_ANNOTATION_FILE, SAMHandlerThread.class));		
		TrackFactory.addThickSeparatorTrack(plot);

		TrackFactory.addReadTracks(
				plot, 
				new SAMDataSource(BAM_DATA_FILE, BAI_DATA_FILE),
				null,
				"Control"
		);
		
		TrackFactory.addReadTracks(
				plot, 
				new SAMDataSource(BAM_DATA_FILE, BAI_DATA_FILE),
				null,
				"Treatment"
		);
		
		panel.setChart(new JFreeChart(plot));
		panel.setPreferredSize(new Dimension(PREVIEW_WIDTH, PREVIEW_HEIGHT));
		panel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		
		RegionDouble region = new RegionDouble(144151000d, 144154000d, new Chromosome("1"));
		
		plot.getDataView().setBpRegion(region, false);
		
		for (View view : plot.getViews()){
			panel.addMouseListener(view);
			panel.addMouseMotionListener(view);
			panel.addMouseWheelListener(view);
		}

		return panel;
	}
}

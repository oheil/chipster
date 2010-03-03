package fi.csc.microarray.client.visualisation.methods.gbrowser.fileFormat;

import java.util.Arrays;

import fi.csc.microarray.client.visualisation.methods.gbrowser.message.BpCoordRegion;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Chromosome;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.RegionContent;

public class SequenceParser extends ConstantRowLengthParser{

	private static FileDefinition fileDef = new FileDefinition(
			Arrays.asList(
					new ColumnDefinition[] {
							
							new ColumnDefinition(ColumnType.CHROMOSOME, Type.LONG, 2),
							new ColumnDefinition(ColumnType.BP_START, Type.LONG, 16),
							new ColumnDefinition(ColumnType.BP_END, Type.LONG, 16),
							new ColumnDefinition(ColumnType.STRAND, Type.STRING, 2),							
							new ColumnDefinition(ColumnType.SEQUENCE, Type.STRING, 64),
							new ColumnDefinition(ColumnType.SKIP, Type.NEWLINE, 1)

					}));
	
	public SequenceParser() {
		
			super(fileDef);
	}

	@Override
	public int getChunkMaxByteLength() {
		return (int)getRowByteLength() * 128;
	}

	@Override
	public long getFilePosition(long readIndex) {
		return readIndex * getRowByteLength();
	}

	@Override
	public long getRowIndex(long filePosition) {
		return filePosition / getRowByteLength();
	}

	@Override
	public RegionContent[] concise(BpCoordRegion readIndexRegion) {

		//Return empty table, otherwise TreeNode gets stuck in calling this again
		return new RegionContent[0];
	}

	@Override
	public BpCoordRegion getBpRegion(long rowIndex) {

		long startBp = (Long)get(rowIndex, ColumnType.BP_START);
		long endBp = (Long)get(rowIndex, ColumnType.BP_END);
		Chromosome chr = (Chromosome)get(rowIndex, ColumnType.CHROMOSOME);

		return new BpCoordRegion(startBp, endBp, chr);
	}

	@Override
	public FileParser clone() {
		FileParser clone = new SequenceParser();
		
		clone.chunk = this.chunk;
			
		return clone;
	}
	
	//For some reason current sequence file starts 10k before the reads sequences
	//Maybe different version of reference sequence?
	@Override
	public Object get(long rowIndex, ColumnType col) {
		
		Object obj = super.get(rowIndex, col);
		
		if(col == ColumnType.BP_START || col == ColumnType.BP_END) {			
			return ((Long)obj) - 10137;
		}
		return obj;
	}

	@Override
	public String getName() {
		return "Chipster sequence file";
	}
}
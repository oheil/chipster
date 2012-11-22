package fi.csc.microarray.client.visualisation.methods.gbrowser.track;

import java.awt.Color;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import fi.csc.microarray.client.visualisation.methods.gbrowser.dataSource.DataSource;
import fi.csc.microarray.client.visualisation.methods.gbrowser.drawable.Drawable;
import fi.csc.microarray.client.visualisation.methods.gbrowser.drawable.TextDrawable;
import fi.csc.microarray.client.visualisation.methods.gbrowser.fileFormat.ColumnType;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.AreaResult;
import fi.csc.microarray.client.visualisation.methods.gbrowser.view.View;

/**
 * Track for placing title texts on top of other tracks.
 *
 */
public class TitleTrack extends Track {

	private Color color;
	private String title;

	public TitleTrack(View view, String title, Color color) {
		super(view, null);
		this.color = color;
		this.title = title;
		height = 10;
	}

	@Override
	public Collection<Drawable> getDrawables() {
		Collection<Drawable> drawables = getEmptyDrawCollection();
		drawables.add(new TextDrawable(5, 10, title, color));
		return drawables;
	}

	public void processAreaResult(AreaResult areaResult) {
		// ignore
	}

	@Override
	public Integer getHeight() {
		return height;
	}
	   
    @Override
    public boolean isStretchable() {
        return false;
    }

    @Override
    public Map<DataSource, Set<ColumnType>> requestedData() {
        return null;
    }

	@Override
	public boolean isConcised() {
		return false;
	}
	
	@Override
	public String getName() {
		return "title";
	}
}
package fi.csc.microarray.client.visualisation.methods.gbrowser.track;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.Drawable;
import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.GBrowserView;
import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.LayoutComponent;
import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.LayoutTool.LayoutMode;
import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.LineDrawable;
import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.TextDrawable;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataResultListener;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.DataType;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Strand;
import fi.csc.microarray.client.visualisation.methods.gbrowser.runtimeIndex.DataThread;

/**
 * Single track inside a {@link GBrowserView}. Typically multiple instances
 * are used to construct what user perceives as a track. 
 */
public abstract class Track implements DataResultListener, LayoutComponent {

	private static final int NAME_VISIBLE_VIEW_RATIO = 20;
	protected GBrowserView view;
	protected List<DataThread> dataThreads = new LinkedList<DataThread>();
	protected Strand strand = Strand.FORWARD;
	protected int layoutHeight;
	protected boolean visible = true;
	protected LayoutMode layoutMode = LayoutMode.FIXED;
	protected LayoutMode defaultLayoutMode = LayoutMode.FIXED;
	private Map<DataThread, Set<DataType>> dataTypeMap = new HashMap<DataThread, Set<DataType>>();
	
	private long maxViewLength = Long.MAX_VALUE;
	private long minViewLength = 0;


	public void setView(GBrowserView view) {
    	this.view = view;
    }
    
    /**
     * @param dataThread
     * @return index of added {@link DataThread}
     */
    public int addDataThread (DataThread dataThread) {
    	this.dataThreads.add(dataThread);
    	return dataThreads.size() - 1;
    }

	/**
	 * Should be called after Track object is created, but can't be merged to constructor, because the coming dataResult event could cause
	 * call to track object before it's constructed.
	 */
	public void initializeListener() {
		if (dataThreads != null) {
			for (DataThread handler : dataThreads) {
				view.getQueueManager().addDataResultListener(handler, this);
			}
		} 
	}

	/**
	 * The method where the actual work of a track typically happens. Each track needs to manage drawables, possibly
	 * caching them.
	 */
	public abstract  Collection<Drawable> getDrawables();
	/**
	 * The view under which this track operates.
	 */
	protected GBrowserView getView() {
		return view;
	}
	
	/**
	 * Check if this track has data.
	 */
	public boolean hasData() {
	    return dataThreads != null;
	}
	
    /**
     * Define data sources and dataTypes that this
     * track needs to operate by calling addDataRequest methods.
     * 
     * This empty default implementation is fine if the track doesn't need any data.
     */
    public void defineDataTypes() {
    	
    };
    
    /**
     * Request data of the specified DataType from the first dataThread. Use this method when there is only
     * one dataThread for this track, otherwise use other other overloaded versions to define the
     * dataThreads explicitly.
     * 
     * @param type
     */
    public void addDataType(DataType type) {

    	addDataType(dataThreads.get(0), type);
    }

    /**
     * Request data of the specified DataType from the given dataThread. Use this method when there are several dataThreads 
     * for this track to define the dataThreads explicitly.
     * 
     * @param type
     */
    public void addDataType(DataThread dataThread, DataType type) {

    	Set<DataType> set = dataTypeMap.get(dataThread);
    	
    	if (set == null) {
    		set = new HashSet<DataType>();
    		dataTypeMap.put(dataThread, set);
    	}
    	
    	set.add(type);
    }

	/**
	 * Utility method, return empty Drawable collection.
	 */
	public Collection<Drawable> getEmptyDrawCollection() {
		return new LinkedList<Drawable>();
	}
	
	/**
	 * @return height of this track in pixels.
	 */
	public int getHeight() {
	    return Math.max(layoutHeight, getMinHeight());
	}
	
	/**
	 * Set height of this track.
	 */
    public void setHeight(int height) {
        this.layoutHeight = height;
    }
	
    /**
     * Determine if the track is visible.
     * 
     * @return false.
     */
    public boolean isVisible() {
    	
        return (visible &&
                getView().getBpRegion().getLength() >= minViewLength &&
                getView().getBpRegion().getLength() < maxViewLength);
    }
    
    /**
     * Set track visibility.
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

	public void setStrand(Strand s) {
		this.strand = s;
	}

	public Strand getStrand() {
		return strand;
	}
	
	/**
	 * Determine if this track represents a reverse strand.
	 * 
	 * @return true if this track represents a reverse strand,
	 * false otherwise.
	 */
    public boolean isReversed() {
        return strand == Strand.REVERSE;
    }
    
    /**
     * Determine if drawable elements inside this track can be
     * expanded to stretch across all available height. 
     */
    public boolean canExpandDrawables() {
        return false;
    }

	private Point2D[] arrowPoints = new Point2D[] { new Point.Double(0, 0.25), new Point.Double(0.5, 0.25), new Point.Double(0.5, 0), new Point.Double(1, 0.5), new Point.Double(0.5, 1), new Point.Double(0.5, 0.75), new Point.Double(0, 0.75), new Point.Double(0, 0.25) };
	private String name = "Track";
	private int fullHeight;
	private int FULL_HEIGHT_MARGIN = 10;

	/**
	 * DOCME
	 * 
	 * Utility method for gettting drawable objects for an arrow figure. The x and y parameters set 
	 * the upper left corner coordinates of the figure and width and height the size of the figure.
	 * This method was placed here to be able to use similar arrows in every track, but more
	 * versatile and separate collection of drawable utilities might be better place in the future.
	 * 
	 * @param x
	 * @param y
	 * @param width
	 * @param height
	 * @return
	 */
	protected Collection<? extends Drawable> getArrowDrawables(int x, int y, int width, int height) {

		Collection<Drawable> parts = getEmptyDrawCollection();

		for (int i = 1; i < arrowPoints.length; i++) {
			Point2D p1 = arrowPoints[i - 1];
			Point2D p2 = arrowPoints[i];

			Point2D p1Scaled = new Point.Double(x + p1.getX() * width, y + p1.getY() * height);
			Point2D p2Scaled = new Point.Double(x + p2.getX() * width, y + p2.getY() * height);

			parts.add(new LineDrawable((int) p1Scaled.getX(), (int) p1Scaled.getY(), (int) p2Scaled.getX(), (int) p2Scaled.getY(), Color.black));
		}

		return parts;
	}
	
	public String getName() {
		return name ;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public boolean isNameVisible(Rectangle rect) {
		return rect.width > getView().getWidth()/NAME_VISIBLE_VIEW_RATIO;
	}

	protected void drawTextAboveRectangle(String text, Collection<Drawable> drawables, Rectangle rect, int offset) {
		drawables.add(new TextDrawable(rect.x < 0 ? 0 : rect.x, rect.y + offset, text, Color.DARK_GRAY));
	}

	public int getMinHeight() {
		return 0;
	}

	public void setFullHeight(Collection<Drawable> drawables) {
		int maxY = 0;
		for (Drawable drawable : drawables) {
			if (drawable.getMaxY() > maxY && view.getWidth() > drawable.x) {
				maxY = drawable.getMaxY();
			}
		}
		
		if (getLayoutMode() == LayoutMode.FULL) {
			maxY += FULL_HEIGHT_MARGIN;
		}
		this.fullHeight = maxY;
	}

	public int getFullHeight() {
		if (getLayoutMode() == LayoutMode.FIXED || getLayoutMode() == LayoutMode.FILL) {
			return getHeight();
		} else {
			return Math.max(fullHeight, this.getHeight());
		}
	}
	
	public void setLayoutMode(LayoutMode mode) {
		this.layoutMode = mode;
	}
	
	public void setDefaultLayoutMode(LayoutMode mode) {
		this.defaultLayoutMode = mode;
	}
	
	public void setDefaultLayoutMode() {
		this.layoutMode = this.defaultLayoutMode;
	}
	
	public LayoutMode getLayoutMode() {
		return layoutMode;
	}

	public void clearDataTypes() {
		this.dataTypeMap.clear();
	}

	public Map<DataThread, Set<DataType>> getDataTypeMap() {
		return dataTypeMap;
	}
	
	/**
	 * Set limits for the track visibility. 
	 * 
	 * @param minViewLength
	 * @param maxViewLength
	 */
	public void setViewLimits(long minViewLength, long maxViewLength) {
		this.minViewLength = minViewLength;
		this.maxViewLength = maxViewLength;
	}
}

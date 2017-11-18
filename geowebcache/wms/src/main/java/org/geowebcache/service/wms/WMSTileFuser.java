/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Arne Kepp, OpenGeo, Copyright 2009
 */
package org.geowebcache.service.wms;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.media.jai.PlanarImage;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geotools.resources.image.ImageUtilities;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.conveyor.Conveyor.CacheResult;
import org.geowebcache.conveyor.ConveyorTile;
import org.geowebcache.filter.request.RequestFilterException;
import org.geowebcache.filter.security.SecurityDispatcher;
import org.geowebcache.grid.BoundingBox;
import org.geowebcache.grid.GridSubset;
import org.geowebcache.grid.OutsideCoverageException;
import org.geowebcache.grid.SRS;
import org.geowebcache.io.ImageDecoderContainer;
import org.geowebcache.io.ImageEncoderContainer;
import org.geowebcache.io.Resource;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.layer.TileLayerDispatcher;
import org.geowebcache.layer.wms.WMSLayer;
import org.geowebcache.mime.ImageMime;
import org.geowebcache.mime.MimeType;
import org.geowebcache.stats.RuntimeStats;
import org.geowebcache.storage.StorageBroker;
import org.geowebcache.util.AccountingOutputStream;
import org.geowebcache.util.ServletUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;

/*
 * It will work as follows
 * 2) Based on the dimensions and bounding box of the request, GWC will determine the smallest available resolution that equals or exceeds the requested resolution.
 * 3) GWC will create a new in-memory raster, based on the best resolution and requested bounding box, and write the appropriate PNG tiles to it. Missing tiles will be requested from WMS.
 * 4) GWC will scale the raster down to the requested dimensions.
 * 5) GWC will then compress the raster to the desired output format and return the image. The image is not cached. 
 */
public class WMSTileFuser{
    private static Log log = LogFactory.getLog(WMSTileFuser.class);
    
    private ApplicationContext applicationContext;
    
    final StorageBroker sb;

    final GridSubset gridSubset;

    final TileLayer layer;

    final ImageMime outputFormat;

    ImageMime srcFormat;

    int reqHeight;

    int reqWidth;

    // The desired extent of the request
    final BoundingBox reqBounds;

    // Boolean reqTransparent;

    // String reqBgColor;

    // For adjustment of final raster
    double xResolution;

    double yResolution;

    // The source resolution
    /* Follows GIS rather than Graphics conventions and so is expressed as physical size of pixel 
     * rather than density.*/
    double srcResolution;

    int srcIdx;

    // Area of tiles being used in tile coordinates
    long[] srcRectangle;

    // The spatial extent of the tiles used to fulfil the request
    BoundingBox srcBounds;
    // 
    BoundingBox canvasBounds;
    /**Canvas dimensions*/
    int[] canvasSize = new int[2];

    static class SpatialOffsets {
        double top;
        double bottom;
        double left;
        double right;
    };
    static class PixelOffsets {
        int top;
        int bottom;
        int left;
        int right;
    };
    
    /** These are values before scaling */
    PixelOffsets canvOfs = new PixelOffsets();

    SpatialOffsets boundOfs = new SpatialOffsets();
    /** Mosaic image*/
    BufferedImage canvas;
    /** Graphics object used for drawing the tiles into a mosaic*/
    Graphics2D gfx;

    /**Layer parameters*/
    private Map<String, String> fullParameters;

    /** Map of all the possible decoders to use*/
    private ImageDecoderContainer decoderMap;
    
    /** Map of all the possible encoders to use*/
    private ImageEncoderContainer encoderMap;

    /** Hints used for writing the BufferedImage on the canvas*/
    private RenderingHints hints;

    private SecurityDispatcher securityDispatcher;

    /**
     *Enum storing the Hints associated to one of the 3 configurations(SPEED, QUALITY, DEFAULT)
     */
    public enum HintsLevel {
        QUALITY(0, "quality"), DEFAULT(1, "default"), SPEED(2, "speed");

        private RenderingHints hints;

        private String mode;

        HintsLevel(int numHint, String mode) {
            this.mode = mode;
            switch (numHint) {
            // QUALITY HINTS
            case 0:
                hints = new RenderingHints(RenderingHints.KEY_COLOR_RENDERING,
                        RenderingHints.VALUE_COLOR_RENDER_QUALITY);
                hints.add(new RenderingHints(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON));
                hints.add(new RenderingHints(RenderingHints.KEY_FRACTIONALMETRICS,
                        RenderingHints.VALUE_FRACTIONALMETRICS_ON));
                hints.add(new RenderingHints(RenderingHints.KEY_ALPHA_INTERPOLATION,
                        RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY));
                hints.add(new RenderingHints(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC));
                hints.add(new RenderingHints(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY));
                hints.add(new RenderingHints(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON));
                hints.add(new RenderingHints(RenderingHints.KEY_STROKE_CONTROL,
                        RenderingHints.VALUE_STROKE_NORMALIZE));
                break;
            // DEFAULT HINTS
            case 1:
                hints = new RenderingHints(RenderingHints.KEY_COLOR_RENDERING,
                        RenderingHints.VALUE_COLOR_RENDER_DEFAULT);
                hints.add(new RenderingHints(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_DEFAULT));
                hints.add(new RenderingHints(RenderingHints.KEY_FRACTIONALMETRICS,
                        RenderingHints.VALUE_FRACTIONALMETRICS_DEFAULT));
                hints.add(new RenderingHints(RenderingHints.KEY_ALPHA_INTERPOLATION,
                        RenderingHints.VALUE_ALPHA_INTERPOLATION_DEFAULT));
                hints.add(new RenderingHints(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR));
                hints.add(new RenderingHints(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_DEFAULT));
                hints.add(new RenderingHints(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT));
                hints.add(new RenderingHints(RenderingHints.KEY_STROKE_CONTROL,
                        RenderingHints.VALUE_STROKE_DEFAULT));
                break;
            // SPEED HINTS
            case 2:
                hints = new RenderingHints(RenderingHints.KEY_COLOR_RENDERING,
                        RenderingHints.VALUE_COLOR_RENDER_SPEED);
                hints.add(new RenderingHints(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_OFF));
                hints.add(new RenderingHints(RenderingHints.KEY_FRACTIONALMETRICS,
                        RenderingHints.VALUE_FRACTIONALMETRICS_OFF));
                hints.add(new RenderingHints(RenderingHints.KEY_ALPHA_INTERPOLATION,
                        RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED));
                hints.add(new RenderingHints(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR));
                hints.add(new RenderingHints(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_SPEED));
                hints.add(new RenderingHints(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_OFF));
                hints.add(new RenderingHints(RenderingHints.KEY_STROKE_CONTROL,
                        RenderingHints.VALUE_STROKE_PURE));
                break;
            }
        }

        public RenderingHints getRenderingHints() {
            return hints;
        }

        public String getModeName(){
            return mode;
        }
        
        public static HintsLevel getHintsForMode(String mode) {

            if(mode!=null){
                if (mode.equalsIgnoreCase(QUALITY.getModeName())) {
                    return QUALITY;
                }else if(mode.equalsIgnoreCase(SPEED.getModeName())){
                    return SPEED;
                }else{
                    return DEFAULT;
                }
            }else{
                return DEFAULT;
            }
        }
    }
    
    
    protected WMSTileFuser(TileLayerDispatcher tld, StorageBroker sb, HttpServletRequest servReq)
            throws GeoWebCacheException {
        this.sb = sb;

        String[] keys = { "layers", "format", "srs", "bbox", "width", "height", "transparent",
                "bgcolor", "hints" };

        Map<String, String> values = ServletUtils.selectedStringsFromMap(servReq.getParameterMap(),
                servReq.getCharacterEncoding(), keys);

        // TODO Parameter filters?

        String layerName = values.get("layers");
        layer = tld.getTileLayer(layerName);
        
        gridSubset = layer.getGridSubsetForSRS(SRS.getSRS(values.get("srs")));

        outputFormat = (ImageMime) ImageMime.createFromFormat(values.get("format"));
        
        List<MimeType> ml = layer.getMimeTypes();
        Iterator<MimeType> iter = ml.iterator();
        
        ImageMime firstMt = null;
        
        if(iter.hasNext()){
            firstMt = (ImageMime)iter.next();
        }
        boolean outputGif = outputFormat.getInternalName().equalsIgnoreCase("gif");
        while (iter.hasNext()) {
            MimeType mt = iter.next();           
            if(outputGif){
                if (mt.getInternalName().equalsIgnoreCase("gif")) {
                    this.srcFormat = (ImageMime) mt;
                    break;
                }
            }
            if (mt.getInternalName().equalsIgnoreCase("png")) {
                this.srcFormat = (ImageMime) mt;
            }
        }
        
        if(srcFormat == null){
            srcFormat = firstMt;
        }

        reqBounds = new BoundingBox(values.get("bbox"));

        reqWidth = Integer.valueOf(values.get("width"));

        reqHeight = Integer.valueOf(values.get("height"));

        fullParameters = layer.getModifiableParameters(servReq.getParameterMap(),
                servReq.getCharacterEncoding());
        if(values.get("hints")!=null){
            hints = HintsLevel.getHintsForMode(values.get("hints")).getRenderingHints();
        }        
    }
    
    @Deprecated
    protected WMSTileFuser(TileLayer layer, GridSubset gridSubset, BoundingBox bounds, int width,
            int height) {
        this.sb = null;
        this.outputFormat = ImageMime.png;
        this.layer = layer;
        this.gridSubset = gridSubset;
        this.reqBounds = bounds;
        this.reqWidth = width;
        this.reqHeight = height;
        this.fullParameters = Collections.emptyMap();
        
        List<MimeType> ml = layer.getMimeTypes();
        Iterator<MimeType> iter = ml.iterator();
        ImageMime firstMt = null;
        
        if(iter.hasNext()){
            firstMt = (ImageMime)iter.next();
        }
        
        while (iter.hasNext()) {
            MimeType mt = iter.next();
            if (mt.getInternalName().equalsIgnoreCase("png")) {
                this.srcFormat = (ImageMime) mt;
                break;
            }
        }
        
        if(srcFormat == null){
            srcFormat = firstMt;
        }
    }

    protected void determineSourceResolution() {
        xResolution = reqBounds.getWidth() / reqWidth;
        yResolution = reqBounds.getHeight() / reqHeight;

        double tmpResolution;
        // We use the smallest one
        if (yResolution < xResolution) {
            tmpResolution = yResolution;
        } else {
            tmpResolution = xResolution;
        }

        log.debug("x res: " + xResolution + " y res: " + yResolution + " tmpResolution: "
                + tmpResolution);

        // Cut ourselves 0.5% slack
        double compResolution = 1.005 * tmpResolution;

        double[] resArray = gridSubset.getResolutions();

        for (srcIdx = 0; srcIdx < resArray.length; srcIdx++) {
            srcResolution = resArray[srcIdx];
            if (srcResolution < compResolution) {
                break;
            }
        }

        if (srcIdx >= resArray.length) {
            srcIdx = resArray.length - 1;
        }

        log.debug("z: " + srcIdx + " , resolution: " + srcResolution + " (" + tmpResolution + ")");

        // At worst, we have the best resolution possible
    }

    protected void determineCanvasLayout() {
        // Find the spatial extent of the tiles needed to cover the desired extent
        srcRectangle = gridSubset.getCoverageIntersection(srcIdx, reqBounds);
        srcBounds = gridSubset.boundsFromRectangle(srcRectangle);

        // We now have the complete area, lets figure out our offsets
        // Positive means that there is blank space to the first tile,
        // negative means we will not use the entire tile
        boundOfs.left =   srcBounds.getMinX() - reqBounds.getMinX();
        boundOfs.bottom = srcBounds.getMinY() - reqBounds.getMinY();
        boundOfs.right =  reqBounds.getMaxX() - srcBounds.getMaxX();
        boundOfs.top =    reqBounds.getMaxY() - srcBounds.getMaxY();

        canvasSize[0] = (int) Math.round(reqBounds.getWidth() / this.srcResolution);
        canvasSize[1] = (int) Math.round(reqBounds.getHeight() / this.srcResolution);

        PixelOffsets naiveOfs = new PixelOffsets();
        // Calculate the corresponding pixel offsets. We'll stick to sane,
        // i.e. bottom left, coordinates at this point
        naiveOfs.left   = (int) Math.round(boundOfs.left   / this.srcResolution);
        naiveOfs.bottom = (int) Math.round(boundOfs.bottom / this.srcResolution);
        naiveOfs.right  = (int) Math.round(boundOfs.right  / this.srcResolution);
        naiveOfs.top    = (int) Math.round(boundOfs.top    / this.srcResolution);
        
        // Find the offsets on the opposite sides.  This is dependent of how the first two were rounded.
        
        // First, find a tile boundary near the canvas edge, then make sure it's on the correct 
        // side to match the corresponding boundOfs, then take the modulo of the naive rounding 
        // based on the boundOfs, then subtract the two and apply the difference to the boundOfs.
        int tileWidth = this.gridSubset.getTileWidth();
        int tileHeight = this.gridSubset.getTileHeight();
        
        canvOfs.left=naiveOfs.left;
        canvOfs.bottom=naiveOfs.bottom;
        
        canvOfs.right = (canvasSize[0]-canvOfs.left)%tileWidth; // Find nearby tile boundary
        canvOfs.right = (Integer.signum(naiveOfs.right)*tileWidth+canvOfs.right)%tileWidth; // Ensure same sign as naive calculation 
        canvOfs.right = canvOfs.right-(naiveOfs.right%tileWidth)+naiveOfs.right; // Find adjustment from naive and apply to naive calculation
        canvOfs.top = (canvasSize[1]-canvOfs.bottom)%tileHeight; // Find nearby tile boundary
        canvOfs.top = (Integer.signum(naiveOfs.top)*tileHeight+canvOfs.top)%tileHeight; // Ensure same sign as naive calculation 
        canvOfs.top = canvOfs.top-(naiveOfs.top%tileHeight)+naiveOfs.top; // Find adjustment from naive and apply to naive calculation
        
        //postconditions
        assert Math.abs(canvOfs.left-naiveOfs.left)<=1;
        assert Math.abs(canvOfs.bottom-naiveOfs.bottom)<=1;
        assert Math.abs(canvOfs.right-naiveOfs.right)<=1;
        assert Math.abs(canvOfs.top-naiveOfs.top)<=1;
        
        if (log.isDebugEnabled()) {
            log.debug("intersection rectangle: " + Arrays.toString(srcRectangle));
            log.debug("intersection bounds: " + srcBounds + " (" + reqBounds + ")");
            log.debug("Bound offsets: " + Arrays.toString(new double[]{boundOfs.left, boundOfs.bottom, boundOfs.right, boundOfs.top}));
            log.debug("Canvas size: " + Arrays.toString(canvasSize) + "(" + reqWidth + ","
                    + reqHeight + ")");
            log.debug("Canvas offsets: " + Arrays.toString(new int[]{canvOfs.left, canvOfs.bottom, canvOfs.right, canvOfs.top}));
        }
    }

    protected void createCanvas() {
        // TODO take bgcolor and transparency from request into account
        // should move this into a separate function

        Color bgColor = null;
        boolean transparent = true;

        if (layer instanceof WMSLayer) {
            WMSLayer wmsLayer = (WMSLayer) layer;
            int[] colorAr = wmsLayer.getBackgroundColor();

            if (colorAr != null) {
                bgColor = new Color(colorAr[0], colorAr[1], colorAr[2]);
            }
            transparent = wmsLayer.getTransparent();
        }

        int canvasType;
        if (bgColor == null && transparent
                && (outputFormat.supportsAlphaBit() || outputFormat.supportsAlphaChannel())) {
            canvasType = BufferedImage.TYPE_INT_ARGB;
        } else {
            canvasType = BufferedImage.TYPE_INT_RGB;
            if (bgColor == null) {
                bgColor = Color.WHITE;
            }
        }

        // Create the actual canvas and graphics object
        canvas = new BufferedImage(canvasSize[0], canvasSize[1], canvasType);
        gfx = (Graphics2D) canvas.getGraphics();

        if (bgColor != null) {
            gfx.setColor(bgColor);
            gfx.fillRect(0, 0, canvasSize[0], canvasSize[1]);
        }
        
        // Hints settings
        RenderingHints hintsTemp = HintsLevel.DEFAULT.getRenderingHints();
        
        if(hints!=null){
            hintsTemp = hints;
        }
        gfx.addRenderingHints(hintsTemp);
    }

    protected void renderCanvas() throws OutsideCoverageException, GeoWebCacheException,
            IOException,Exception {        
        
        // Now we loop over all the relevant tiles and write them to the canvas,
        // Starting at the bottom, moving to the right and up
        
        // Bottom row of tiles, in tile coordinates
        long starty = srcRectangle[1];
        
        // gridy is the tile row index
        for (long gridy = starty; gridy <= srcRectangle[3]; gridy++) {

            int tiley = 0;
            int canvasy = (int) (srcRectangle[3] - gridy) * gridSubset.getTileHeight();
            int tileHeight = gridSubset.getTileHeight();

            if (canvOfs.top > 0) {
                // Add padding
                canvasy += canvOfs.top;
            } else {
                // Top tile is cut off
                if (gridy == srcRectangle[3]) {
                    // This one starts at the top, so canvasy remains 0
                    tileHeight = tileHeight + canvOfs.top;
                    tiley = -canvOfs.top;
                } else {
                    // Offset that the first tile contributed,
                    // rather, we subtract what it did not contribute
                    canvasy += canvOfs.top;
                }
            }

            if (gridy == srcRectangle[1] && canvOfs.bottom < 0) {
                // Check whether we only use part of the first tiles (bottom row)
                // Offset is negative, slice the bottom off the tile
                tileHeight += canvOfs.bottom;
            }

            long startx = srcRectangle[0];
            for (long gridx = startx; gridx <= srcRectangle[2]; gridx++) {

                long[] gridLoc = { gridx, gridy, srcIdx };

                ConveyorTile tile = new ConveyorTile(sb, layer.getName(), gridSubset.getName(),
                        gridLoc, srcFormat, fullParameters, null, null);
                
                tile.setTileLayer(layer);
                
                securityDispatcher.checkSecurity(tile);
                
                // Check whether this tile is to be rendered at all
                try {
                    layer.applyRequestFilters(tile);
                } catch (RequestFilterException e) {
                    log.debug(e.getMessage(),e);
                    continue;
                }

                layer.getTile(tile);
                // Selection of the resource input stream
                Resource blob = tile.getBlob();
                // Extraction of the image associated with the defined MimeType
                String formatName = srcFormat.getMimeType();
                BufferedImage tileImg = decoderMap.decode(formatName, blob, 
                        decoderMap.isAggressiveInputStreamSupported(formatName), null);

                int tilex = 0;
                int canvasx = (int) (gridx - startx) * gridSubset.getTileWidth();
                int tileWidth = gridSubset.getTileWidth();

                if (canvOfs.left > 0) {
                    // Add padding
                    canvasx += canvOfs.left;
                } else {
                    // Leftmost tile is cut off
                    if (gridx == srcRectangle[0]) {
                        // This one starts to the left top, so canvasx remains 0
                        tileWidth = tileWidth + canvOfs.left;
                        tilex = -canvOfs.left;
                    } else {
                        // Offset that the first tile contributed,
                        // rather, we subtract what it did not contribute
                        canvasx += canvOfs.left;
                    }
                }

                if (gridx == srcRectangle[2] && canvOfs.right < 0) {
                    // Check whether we only use part of the first tiles (bottom row)
                    // Offset is negative, slice the bottom off the tile
                    tileWidth = tileWidth + canvOfs.right;
                }

                // TODO We should really ensure we can never get here
                if (tileWidth == 0 || tileHeight == 0) {
                    log.debug("tileWidth: " + tileWidth + " tileHeight: " + tileHeight);
                    continue;
                }

                // Cut down the tile to the part we want
                if (tileWidth != gridSubset.getTileWidth()
                        || tileHeight != gridSubset.getTileHeight()) {
                    log.debug("tileImg.getSubimage(" + tilex + "," + tiley + "," + tileWidth + ","
                            + tileHeight + ")");
                    tileImg = tileImg.getSubimage(tilex, tiley, tileWidth, tileHeight);
                }

                // Render the tile on the big canvas
                log.debug("drawImage(subtile," + canvasx + "," + canvasy + ",null) "
                        + Arrays.toString(gridLoc));

                gfx.drawImage(tileImg, canvasx, canvasy, null); // imageObserver
            }
        }

        gfx.dispose();
    }

    protected void scaleRaster() {
        if (canvasSize[0] != reqWidth || canvasSize[1] != reqHeight) {
            BufferedImage preTransform = canvas;

            canvas = new BufferedImage(reqWidth, reqHeight, preTransform.getType());

            Graphics2D gfx = canvas.createGraphics();

            AffineTransform affineTrans = AffineTransform.getScaleInstance(((double) reqWidth)
                    / preTransform.getWidth(), ((double) reqHeight) / preTransform.getHeight());

            log.debug("AffineTransform: " + (((double) reqWidth) / preTransform.getWidth()) + ","
                    + +(((double) reqHeight) / preTransform.getHeight()));
            // Hints settings
            RenderingHints hintsTemp = HintsLevel.DEFAULT.getRenderingHints();
            
            if(hints!=null){
                hintsTemp = hints;
            }
            gfx.addRenderingHints(hintsTemp);
            gfx.drawRenderedImage(preTransform, affineTrans);
            gfx.dispose();
        }
    }

    protected void writeResponse(HttpServletResponse response, RuntimeStats stats)
            throws IOException, OutsideCoverageException, GeoWebCacheException,Exception {
        determineSourceResolution();
        determineCanvasLayout();
        createCanvas();
        renderCanvas();
        scaleRaster();       
        
        AccountingOutputStream aos=null;
        RenderedImage finalImage =null;
        try{
          finalImage = canvas;

          response.setStatus(HttpServletResponse.SC_OK);
          response.setContentType(this.outputFormat.getMimeType());
          response.setCharacterEncoding("UTF-8");

          ServletOutputStream os = response.getOutputStream();
          aos = new AccountingOutputStream(os);         
          
          // Image encoding with the associated writer
          encoderMap.encode(finalImage, outputFormat, aos,
                    encoderMap.isAggressiveOutputStreamSupported(outputFormat.getMimeType()), null);
          
          log.debug("WMS response size: " + aos.getCount() + "bytes.");
          stats.log(aos.getCount(), CacheResult.WMS);
        } catch (Exception e) {
        	log.debug("IOException writing untiled response to client: " + e.getMessage(),e);
        	
        	// closing the stream
        	if(aos!=null){
        		IOUtils.closeQuietly(aos);
        	}
        	
        	// releasing Image
        	if(finalImage!=null){
        		ImageUtilities.disposePlanarImageChain(PlanarImage.wrapRenderedImage(finalImage));
        	}
        }        
    }
	
    /**
     * Setting of the ApplicationContext associated for extracting the related beans
     * 
     * @param context
     * @throws BeansException
     */
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        applicationContext = context; 
        decoderMap = applicationContext.getBean(ImageDecoderContainer.class);
        encoderMap = applicationContext.getBean(ImageEncoderContainer.class);
        
    }

    /**
     * Setting of the hints configuration taken from the WMSService
     * @param hintsConfig
     */
    public void setHintsConfiguration(String hintsConfig) {
        if(hints==null){
            hints = HintsLevel.getHintsForMode(hintsConfig).getRenderingHints();
        }
        
    }

    public void setSecurityDispatcher(SecurityDispatcher securityDispatcher) {
        this.securityDispatcher = securityDispatcher;
    }
    
    
}

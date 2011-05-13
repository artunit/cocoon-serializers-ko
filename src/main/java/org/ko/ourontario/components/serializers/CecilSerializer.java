//Cecil - v. 1.0, art rhyno http://projectconifer.ca
//	Knowledge Ontario serializer for tiles, using JAI
//	July 2009
//
//  Rev: May 2011
//      
/*
 */
package org.ko.ourontario.components.serializers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.avalon.framework.activity.Disposable;
import org.apache.avalon.framework.service.Serviceable;
import org.apache.avalon.framework.service.ServiceException;
import org.apache.avalon.framework.service.ServiceManager;
import org.apache.avalon.framework.service.ServiceSelector;


import org.apache.avalon.excalibur.pool.Recyclable;
import org.apache.avalon.framework.configuration.Configurable;
import org.apache.avalon.framework.configuration.Configuration;
import org.apache.avalon.framework.configuration.ConfigurationException;
import org.apache.avalon.framework.parameters.Parameters;

import org.apache.cocoon.ProcessingException;
import org.apache.excalibur.source.Source;
import org.apache.cocoon.environment.SourceResolver;
import org.apache.cocoon.environment.http.HttpEnvironment;
import org.apache.cocoon.serialization.AbstractSerializer;
import org.apache.cocoon.serialization.Serializer;
import org.apache.cocoon.sitemap.SitemapModelComponent;
import org.xml.sax.SAXException;

import com.sun.media.jai.codec.*;

import java.awt.*;
import java.awt.color.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.awt.image.renderable.ParameterBlock;
import java.awt.image.WritableRaster;
import java.awt.Toolkit;

import java.lang.Math;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier; 
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import javax.media.jai.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.IOException;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Vector;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.NamespaceSupport;
import org.xml.sax.SAXException;

import org.w3c.dom.Node;

//lucene
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.search.Hits;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.queryParser.ParseException;

public class CecilSerializer 
	extends AbstractSerializer 
	implements Configurable, Disposable, Serviceable 
{
	public final String TILE_NAMESPACE = "http://ourontario.ca/tile/1.0";

	//this is the scale of an image reduction
	public final int SCALE_FACTOR = -2;

	private final int START_STATE = 0;
	private final int IN_TILE_STATE = 1;
	private final int IN_CONTENT_STATE = 2;


	/** The component manager */
	protected ServiceManager manager;

	/** The serializer component selector */
	protected ServiceSelector selector;

	/** The image stream where the tile will be written */
	protected ImageOutputStream CecilOutput;

	/** The current state */
	protected int state = START_STATE;

	/** The resolver to get sources */
	protected SourceResolver resolver;

	/** Temporary byte buffer to read source data */
	protected byte[] buffer = new byte[1024];

	/** Serializer used when in IN_CONTENT state */
	protected Serializer serializer;

	/** Current depth of the serialized content */
	protected int contentDepth;

	/** Used to collect namespaces */
	private NamespaceSupport nsSupport = new NamespaceSupport();

	/** Store exception */
	private SAXException exception = null;

	int tileWidth=512;
	int tileHeight=512;
    int fudge = 5;

	public void service(ServiceManager manager) throws ServiceException {
		this.manager = manager;
		this.resolver = (SourceResolver)this.manager.lookup(SourceResolver.ROLE);
	}//service

	//i think this is overruled by the sitemap defN
	public String getMimeType() {
		return "image/jpeg";
	}//getMimeType

	/*
		serializer only has a few parameters, width, height, and fudge. this can be
		confusing because a tiled image has its own metrics, independent of
		the tiling done for a web application.
	*/
	public void configure(Configuration conf) throws ConfigurationException {
        	Configuration[] parameters = conf.getChildren("parameter");

		for (int i = 0; i < parameters.length; i++) {
			String name = parameters[i].getAttribute("name");
                if ("width".equals(name)) {
				    tileWidth = safeInt(parameters[i].
					    getAttribute("value"));
			    }//if
                if ("height".equals(name)) {
                    tileHeight = safeInt(parameters[i].
                        getAttribute("value"));
                }//if
                if ("fudge".equals(name)) {
                    fudge = safeInt(parameters[i].
                        getAttribute("value"));
                }//if
		}//for
	}//configure

	public void startDocument() throws SAXException {
		this.state = START_STATE;
		try {
			this.CecilOutput = ImageIO.createImageOutputStream(this.output);
		} catch (IOException e) {
			throw this.exception = new SAXException(e);
		}//try 
	}//startDocument

	public void startPrefixMapping(String prefix, String uri) throws SAXException {
		if (state == IN_CONTENT_STATE) {
			super.startPrefixMapping(prefix, uri);
		} else {
			// Register it if it's not our own namespace (useless to content)
			if (!uri.equals(TILE_NAMESPACE)) 
				this.nsSupport.declarePrefix(prefix, uri);
		}//if state
	}//startPrefixMapping

	public void startElement(String namespaceURI, String localName, String qName, Attributes atts)
        	throws SAXException 
	{

		if (this.exception != null) 
			throw this.exception;

		switch (state) {
			case START_STATE:
				// expecting "tileInfo" as the first element
				if (namespaceURI.equals(TILE_NAMESPACE) && localName.equals("tileInfo")) {
					this.nsSupport.pushContext();
					this.state = IN_TILE_STATE;
				} else {
					throw this.exception =
						new SAXException("Expecting 'tileInfo' root element (got '" + 
							localName + "')");
                		}//if namespaceURI
				break;
			case IN_TILE_STATE:
				// expecting "image" element
				if (namespaceURI.equals(TILE_NAMESPACE) && localName.equals("image")) {
					this.nsSupport.pushContext();
					createTile(atts);
				} else {
					throw this.exception =
						new SAXException("Expecting 'image' element (got '" + 
							localName + "')");
				}//if namespaceURI
				break;
			case IN_CONTENT_STATE:
				this.contentDepth++;
				super.startElement(namespaceURI, localName, qName, atts);
				break;
		}//switch
	}//startElement

	public void characters(char[] buffer, int offset, int length) throws SAXException {
		if (this.state == IN_CONTENT_STATE && this.contentDepth > 0) 
			super.characters(buffer, offset, length);
	}//characters

	protected void createTile(Attributes atts) throws SAXException {

 		long elapsedTimeMillis = 0;
		long start = System.currentTimeMillis();

		Iterator readers = null;
		int totWidth = 0;
		int totHeight = 0;

        int[] imagecoords = new int[4];

		String src = atts.getValue("src");

		//can't do a thing without src
		if (src == null) {
			throw this.exception =
				new SAXException("No src given for image");
		}//if src 

		String format = "jpeg";
		if (atts.getValue("format") != null)
			format = atts.getValue("format");

		//image width
		int w = safeInt(atts.getValue("w")) > 0 ? 
			safeInt(atts.getValue("w")): 256;

		//image height
		int h = safeInt(atts.getValue("h")) > 0 ? 
			safeInt(atts.getValue("h")): 256;

        String hlString = getHlString(w,h,atts.getValue("query"),
            atts.getValue("index"), imagecoords);

		PlanarImage objImage = null;

		String overlay = atts.getValue("overlay");

		//x1 coordinate
		int x1 = safeInt(atts.getValue("x1")) > 0 && hlString == null ? 
			safeInt(atts.getValue("x1")): imagecoords[0];

		//y1 coordinate
		int y1 = safeInt(atts.getValue("y1")) > 0 && hlString == null ? 
			safeInt(atts.getValue("y1")): imagecoords[1];

		//x2 coordinate
		int x2 = safeInt(atts.getValue("x2")) > 0 && hlString == null ? 
			safeInt(atts.getValue("x2")): imagecoords[2];

		//y2 coordinate
		int y2 = safeInt(atts.getValue("y2")) > 0 && hlString == null ? 
			safeInt(atts.getValue("y2")): imagecoords[3];

		//image rotate
        int r = 0; 
        if (atts.getValue("r") != null) {
		    r = safeInt(atts.getValue("r")) > 0 &&
			    safeInt(atts.getValue("r")) < 360? 
			    Integer.parseInt(atts.getValue("r")): 0;
        }//if

		//image zoom
		int z = safeInt(atts.getValue("z")) != 0 ? 
			Integer.parseInt(atts.getValue("z")): 1;

		Iterator writers = ImageIO.getImageWritersByFormatName(format);
		ImageWriter writer = (ImageWriter)writers.next();
		writer.setOutput(this.CecilOutput);

		try {
			BufferedImage Cecil = null;
			
			int tileX = (int)Math.floor(x1/tileWidth);
			int tileY = (int)Math.floor(y1/tileHeight);

			//System.out.println("tileX is " + tileX + " - tileY is " + tileY);

			if (src.endsWith(".jp2"))
                		readers = ImageIO.getImageReadersByFormatName("jpeg2000");
			else if (src.endsWith(".tif"))
                		readers = ImageIO.getImageReadersByFormatName("tiff");
			else if (src.endsWith(".png"))
                		readers = ImageIO.getImageReadersByFormatName("png");
			else
                		readers = ImageIO.getImageReadersByFormatName("jpg");

			ImageReader reader = (ImageReader)readers.next();

			ImageInputStream iis = ImageIO.createImageInputStream(new File(src));

			reader.setInput(iis,false,true);

            ImageReadParam param = reader.getDefaultReadParam();

            Rectangle rect = new Rectangle(x1,y1,(x2 - x1),(y2 - y1));
            param.setSourceRegion(rect);

            Cecil = reader.read(0, param);

            int transparency = Cecil.getColorModel().getTransparency();

            if (atts.getValue("highlights") != null || hlString != null) {
				Cecil = sortOutHighlight(Cecil, w, h, z, reader, param, 
					x1, y1, x2, y2, atts, transparency, start, hlString);
			} else {
				if (w != (x2 - x1) || h != (y2 - y1)) {
					Cecil = copy(Cecil, 0, 0, w, h, z, getDefaultConfiguration().
                                    		createCompatibleImage(w, h, transparency));
				}//if

			}//if highlights

			writer.write(Cecil);
		} catch (Exception e) {
			throw this.exception = new SAXException(e);
        }//try	
 			
		//System.out.println("final-> " + (System.currentTimeMillis()-start));

	}//createTile


	public BufferedImage sortOutRotate(BufferedImage image, int r) 
	{

		float cx=image.getWidth()/2;
		float cy=image.getHeight()/2;			

		ParameterBlock params= new ParameterBlock();
		params.addSource(image);
		params.add(cx);
		params.add(cy);

		params.add(degreesToRadians(r));
		params.add(Interpolation.getInstance(Interpolation.INTERP_BICUBIC));

		RenderedOp rotatedImg = JAI.create("rotate",params,null);
		return rotatedImg.getAsBufferedImage(); 
	}//sortOutRotate

    public Vector<Document> getResults(String search, String indexsrc)
    {
        Vector<Document> results = new Vector<Document>();
    
        try {
            File indexDirectory = new File(indexsrc);
            if (!indexDirectory.exists() || !indexDirectory.isDirectory()) {
                return null;
            }
            IndexSearcher is = new IndexSearcher(indexsrc);
            Set set = new HashSet();
            Analyzer analyzer = new StandardAnalyzer(set);
            QueryParser parser = new QueryParser("word", analyzer);
            Query query = parser.parse(search);
            Hits hits = is.search(query);
            for (int i=0; i<hits.length(); i++) 
                results.add(hits.doc(i));
            is.close();
        } catch (ParseException pe) {
            System.out.println("ParseException exception " + pe.toString());
            return null;
        } catch (IOException ioe) {
            //no index so no duplicate, but leave alone if other IO problem
            System.out.println("IOException " + ioe.toString());
            return null;
        } catch (Exception Ex) {
            System.out.println("Exception " + Ex.toString());
            return null;
        }//try

        return results;
    }

    public String getHlString(int width, int height, String query, String indexsrc, int[] imgcoords)
    {
        String hlList = null;
        int bestPt,leftPtX,leftPtY,rightPtX,rightPtY,highCnt;
        int x1, y1, x2, y2;

        bestPt = leftPtX = leftPtY = rightPtX = rightPtY = highCnt = 0;

        if (query == null || indexsrc == null) {
            imgcoords[0] = imgcoords[1] = imgcoords[2] = imgcoords[3] = 0;
            return hlList;
        }
        hlList = "";
        Vector<Document> wordMatches = getResults(query,indexsrc);
        if (wordMatches.size() == 0) {
            imgcoords[2] = width;
            imgcoords[3] = height;
            return hlList;
        }
        Vector<Object> matchSet = new Vector<Object>();
            
        /*
            take each term and count up how many terms are close
            in the same tile area
        */
        for (int i=0;i<wordMatches.size();i++){
            int posCnt = 0;
            Vector<Integer> coordInd = new Vector<Integer>();

            Document doc = (Document) wordMatches.get(i);
            leftPtX = safeInt(doc.get("x1"));
            leftPtY = safeInt(doc.get("y1"));

            for (int j=i+1;j<wordMatches.size();j++){
                Document termdoc = (Document) wordMatches.get(j);
                rightPtX = safeInt(termdoc.get("x2"));
                rightPtY = safeInt(termdoc.get("y2"));

                int gap1 = Math.abs(rightPtX - leftPtX);
                int gap2 = Math.abs(rightPtY - leftPtY);

                if (gap1 < width && gap2 < height)
                    coordInd.add(j);
            }//for j
            matchSet.add(coordInd);
            if (coordInd.size() > highCnt) {
                bestPt = i;
                highCnt = coordInd.size();
            }
        }//for i

        //list of coordInd - we want the set with most matches
        Vector<Integer> cluster = (Vector<Integer>)matchSet.get(bestPt);

        //we also need the term that started the cluster
        Document tileDoc = (Document) wordMatches.get(bestPt);
        Vector<Document> tileDocs = new Vector<Document>();
        tileDocs.add(tileDoc);

        for (int i=0;i<cluster.size();i++) {
            int tileInd = cluster.get(i);
            tileDoc = (Document) wordMatches.get(tileInd);
            tileDocs.add(tileDoc);
        }//for

        //ok, now we have all the coordinates in the space allowed
        //our next step is to try to center them
        x1 = y1 = x2 = y2 = 0;

        for (int i=0;i<tileDocs.size();i++) {
            tileDoc = (Document) tileDocs.get(i);

            //find lowest left point
            if (safeInt(tileDoc.get("x1")) < x1 || x1 == 0)
                x1 = safeInt(tileDoc.get("x1"));
            if (safeInt(tileDoc.get("y1")) < y1 || y1 == 0)
                y1 = safeInt(tileDoc.get("y1"));

            //find highest right point
            if (safeInt(tileDoc.get("x2")) > x2 || x2 == 0)
                x2 = safeInt(tileDoc.get("x2"));
            if (safeInt(tileDoc.get("y2")) > y2 || y2 == 0)
                y2 = safeInt(tileDoc.get("y2"));
        }//for

        //calculate shift values
        int xShift = Math.round((width - (x2 - x1))/2);
        int yShift = Math.round((height - (y2 - y1))/2);

        x1 -= xShift;
        y1 -= yShift;
        x2 += xShift;
        y2 += yShift;

        for (int i=0;i<tileDocs.size();i++) {
            tileDoc = (Document) tileDocs.get(i);
            leftPtX = safeInt(tileDoc.get("x1"));
            leftPtY = safeInt(tileDoc.get("y1"));
            rightPtX = safeInt(tileDoc.get("x2"));
            rightPtY = safeInt(tileDoc.get("y2"));

            //change numbers to reflect sizing and not coordinates 
            rightPtX -= leftPtX;
            rightPtY -= leftPtY; 
            //fudge helps with highlight
            rightPtY += fudge; 
            leftPtX -= x1;
            leftPtY -= y1;

            if (leftPtX > -1 && leftPtY > -1 && rightPtX > -1 && rightPtY > -1) {
                String hlCoords = leftPtX + "," + leftPtY + "-" +
                    rightPtX + "," + rightPtY;
                if (hlList.length() > 0)
                    hlList += " ";
                hlList += hlCoords;
            }//if
        }//for
            
        imgcoords[0] = x1; 
        imgcoords[1] = y1;
        imgcoords[2] = x2;
        imgcoords[3] = y2;
        
        return hlList;
    }

	
	public BufferedImage sortOutHighlight(BufferedImage image, int w, int h, int z,
		ImageReader reader, ImageReadParam param, int x1, int y1, int x2, int y2,
		Attributes atts, int transparency,long start, String hlList)
		throws java.io.IOException
	{

		BufferedImage hlImg = null;
		BufferedImage origTile = null;
		boolean boldEffect = false;

		hlImg = copy(image, 0, 0, w, h, z, getDefaultConfiguration().
			createCompatibleImage(w, h, transparency));
                                
		String hlString = hlList;

        if (hlString == null)
            hlString = atts.getValue("highlights");

		Graphics2D g2 = hlImg.createGraphics();
		g2.setComposite(makeComposite(0.25F));

		if (atts.getValue("color") != null) {
			String color = atts.getValue("color");
			if (color.indexOf("gray") != -1)
				g2.setPaint(Color.gray);
			else if (color.indexOf("lightgray") != -1)
				g2.setPaint(Color.lightGray);
			else if (color.indexOf("darkgray") != -1)
				g2.setPaint(Color.darkGray);
			else if (color.indexOf("black") != -1)
				g2.setPaint(Color.black);
			else if (color.indexOf("red") != -1)
				g2.setPaint(Color.red);
			else if (color.indexOf("pink") != -1)
				g2.setPaint(Color.pink);
			else if (color.indexOf("orange") != -1)
				g2.setPaint(Color.orange);
			else if (color.indexOf("green") != -1)
				g2.setPaint(Color.green);
			else if (color.indexOf("magenta") != -1)
				g2.setPaint(Color.magenta);
			else if (color.indexOf("cyan") != -1)
				g2.setPaint(Color.cyan);
			else if (color.indexOf("white") != -1) {
				origTile= reader.read(0, param);
				origTile= copy(origTile, 0, 0, w, h, z, getDefaultConfiguration().
					createCompatibleImage(w, h, transparency));
				g2.setComposite(makeComposite(0.50F));
				boldEffect = true;
				g2.setPaint(Color.white);
				Rectangle rectangle = new
					Rectangle(0, 0, w, h);
				g2.fill(rectangle);
				g2.draw(rectangle);
			} else
				g2.setPaint(Color.blue);
		} else {
			g2.setPaint(Color.blue);
		}//if atts

		//coords format is 20,30-10,10

		StringTokenizer strTok = new StringTokenizer(hlString);

		while (strTok.hasMoreElements()) {
			int[] coords = new int[4];
			if (sortOutCoords(coords,
				(String) strTok.nextElement(),x1,y1,(x2 - x1),(y2 - y1)))
			{
				if (!boldEffect) {
					Rectangle rectangle = new 
						Rectangle(coords[0], coords[1], 
						coords[2], coords[3]);
						g2.fill(rectangle);
						g2.draw(rectangle);
				} else {
					g2.drawImage(origTile.getSubimage(coords[0], coords[1], 
						coords[2],
						coords[3]),
						coords[0],
						coords[1], 
						null);
				}//if
			}//if
		}//while
		g2.dispose();

		return hlImg;

	}//sortOutHighlight

	public void writeOutPng(ImageWriter writer, BufferedImage Cecil)
		throws SAXException 
	{
		try {
			ImageWriteParam imageWriteParam = writer.
				getDefaultWriteParam();			
			ImageTypeSpecifier imageTypeSpecifier = new 
				ImageTypeSpecifier(Cecil);			
			IIOMetadata metadata = writer.
				getDefaultImageMetadata(imageTypeSpecifier, 
				imageWriteParam);
			String sFormat = "javax_imageio_png_1.0";			
			Node node = metadata.getAsTree(sFormat);
			metadata.setFromTree(sFormat, node);			
			writer.write(new IIOImage(Cecil, null, metadata));
		} catch (Exception e) {
			throw this.exception = new SAXException(e);
        	}//try
	}//writeOutPng
	
	public PlanarImage bufferedImageToPlanarImage(BufferedImage bi) 
	{
		ParameterBlock pb = new ParameterBlock();
		pb.add(bi);
		
		return (PlanarImage)JAI.create("AWTImage", pb);	
	}//bufferedImageToPlanarImage

	public boolean sortOutCoords(int[] coords, String coordStr, 
		int x, int y, int w, int h) 
	{

		String tmpCoord = "";
		int breakPt = coordStr.indexOf("-");

		if (breakPt == -1)
			return false;

		tmpCoord = coordStr.substring(0,breakPt);

		int breakPt2 = tmpCoord.indexOf(",");
		if (breakPt2 == -1)
			return false;

		coords[0] = safeInt(tmpCoord.substring(0,breakPt2));
		coords[1] = safeInt(tmpCoord.substring(++breakPt2));

		tmpCoord = coordStr.substring(++breakPt);

		breakPt2 = tmpCoord.indexOf(",");
		if (breakPt2 == -1)
			return false;

		coords[2] = safeInt(tmpCoord.substring(0,breakPt2));
		coords[3] = safeInt(tmpCoord.substring(++breakPt2));

		int width = x+w;
		int height = y+h;

		if (coords[0] > width || coords[0] < 0)
			coords[0] = 0;
		if (coords[1] > height || coords[1] < 0)
			coords[1] = 0;
		if (coords[2] > width || coords[2] < 0)
			coords[2] = width;
		if (coords[3] > height || coords[3] < 0)
			coords[3] = height;

		
		return true;
		
	}//sortOutCoords
    
	public GraphicsConfiguration getDefaultConfiguration() 
	{
        	GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        	GraphicsDevice gd = ge.getDefaultScreenDevice();
        	return gd.getDefaultConfiguration();    
	}//getDefaultConfiguration
    
	public BufferedImage toCompatibleImage(BufferedImage image, 
		GraphicsConfiguration gc) 
	{
		if (gc == null)
            		gc = getDefaultConfiguration();

        	int w = image.getWidth();
        	int h = image.getHeight();
        	int transparency = image.getColorModel().getTransparency();

        	BufferedImage result = gc.createCompatibleImage(w, h, transparency);
        	Graphics2D g2 = result.createGraphics();
        	g2.drawRenderedImage(image, null);
        	g2.dispose();

        	return result;    
	}//toCompatibleImage

    
	public BufferedImage copy(BufferedImage source, int x1, int y1, int x2, int y2, int z, BufferedImage target) 
	{
        	Graphics2D g2 = target.createGraphics();
        	g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, 
			RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        	double scalex = (double) target.getWidth()/ source.getWidth();
        	double scaley = (double) target.getHeight()/ source.getHeight();
        	AffineTransform xform = AffineTransform.getScaleInstance(scalex, scaley);
        	g2.drawRenderedImage(source, xform);
        	g2.dispose();

        	return target.getSubimage(x1, y1, x2 - x1, y2 - y1);    
	}//copy

	public AlphaComposite makeComposite(float alpha) {
    		int type = AlphaComposite.SRC_OVER;
    		return(AlphaComposite.getInstance(type, alpha));
	}//AlphaComposite 

	public BufferedImage revampRaster(Raster tile, ColorModel cm) {
		BufferedImage revImg = null;

		DataBuffer dataBuffer = tile.getDataBuffer();
		WritableRaster wr = tile.
			createWritableRaster(tile.getSampleModel(), 
			dataBuffer, null);
       		revImg = new BufferedImage(cm,
                	wr,
			cm.isAlphaPremultiplied(),
			null);

		return revImg;
	}//revampRaster

	public BufferedImage convertRenderedImage(RenderedImage img) {

		if (img instanceof BufferedImage) 
			return (BufferedImage)img;	


		ColorModel cm = img.getColorModel();
		int width = img.getWidth();
		int height = img.getHeight();
		WritableRaster raster = cm.createCompatibleWritableRaster(width, height);
		boolean isAlphaPremultiplied = cm.isAlphaPremultiplied();
		//Hashtable properties = new Hashtable();
		Hashtable<String, Object> properties = new Hashtable<String, Object>();
		String[] keys = img.getPropertyNames();
		if (keys!=null) {
			for (int i = 0; i < keys.length; i++) 
				properties.put(keys[i], img.getProperty(keys[i]));
		}//if
		BufferedImage result = new BufferedImage(cm, raster, isAlphaPremultiplied, properties);

		img.copyData(raster);
		return result;
	}//convertRenderedImage

	public int safeInt(String theVal) {
        if (theVal == null)
            return -1;
		int theNum = -1;
        String numVal = theVal;
		try {
			int dotPos = numVal.indexOf(".");
			if (dotPos != -1)
				numVal = numVal.substring(0,dotPos);
			theNum = Integer.parseInt(numVal);
		} catch(Exception ex) {        	
			System.out.println(theVal + " leads to Number problem " + ex.toString() +
                " returning " + theNum);
		}//try 
	
		return theNum;    
	}//safeInt
    
	public float degreesToRadians(int degrees) {
		return (float)((degrees*Math.PI)/180.0F);    
	}//degreesToRadians

	public RenderedImage readTiled(File f, int tileWidth, int
		tileHeight) throws IOException {

      		ImageInputStream iis = ImageIO.createImageInputStream(f);
      		ParameterBlockJAI pbj = new ParameterBlockJAI("ImageRead");

      		ImageLayout layout = new ImageLayout();
      		layout.setTileWidth(tileWidth);
      		layout.setTileHeight(tileHeight);          

      		RenderingHints hints = new RenderingHints(JAI.KEY_IMAGE_LAYOUT,
			layout);

      		pbj.setParameter("Input", iis);
      		return JAI.create("ImageRead", pbj, hints);
	}//readTiled

	public void endElement(String namespaceURI, String localName, String qName)
		throws SAXException 
	{
        	if (this.exception != null) 
            		throw this.exception;

        	if (state == IN_CONTENT_STATE) {
			super.endElement(namespaceURI, localName, qName);
			this.contentDepth--;

			if (this.contentDepth == 0) {
				// End of this entry

				// close all declared namespaces.
				Enumeration prefixes = this.nsSupport.getPrefixes();
                
				while (prefixes.hasMoreElements()) {
                    			String prefix = (String) prefixes.nextElement();
                    			super.endPrefixMapping(prefix);
                		}//while

                		super.endDocument();
                		super.setConsumer(null);
                		this.selector.release(this.serializer);
                		this.serializer = null;

                		// Go back to listening for entries
                		this.state = IN_TILE_STATE;
            		}//if this.contentDepth
        	} else {
            		this.nsSupport.popContext();
        	}//if state
	}//endElement
    
	public void endDocument() throws SAXException {
		try {
			this.CecilOutput.close();
        	} catch (IOException ioe) {
            		throw new SAXException(ioe);
        	}//try    
	}//endDocument

	public void recycle() {
        	this.exception = null;
        	if (this.serializer != null) 
            		this.selector.release(this.serializer);

        	if (this.selector != null) 
            		this.manager.release(this.selector);
        
		this.nsSupport.reset();
        	super.recycle();    
	}//recycle
    
	public void dispose() {
        	if ( this.manager != null ) {
            		this.manager.release( this.resolver );
            		this.resolver = null;
            		this.manager = null;
        	}//if
	}//dispose
}//Cecil

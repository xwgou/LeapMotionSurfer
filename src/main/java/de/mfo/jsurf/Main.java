/*
 *    Copyright 2008 Christian Stussak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.mfo.jsurf;


import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.io.FileReader;
import java.util.Properties;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.apache.commons.cli.*;


import de.mfo.jsurf.gui.JSurferRenderPanel;
import de.mfo.jsurf.rendering.cpu.AntiAliasingPattern;
import de.mfo.jsurf.rendering.cpu.CPUAlgebraicSurfaceRenderer;
import de.mfo.jsurf.rendering.cpu.CPUAlgebraicSurfaceRenderer.AntiAliasingMode;
import de.mfo.jsurf.util.FileFormat;

public class Main {	
	
    static int size = 800;
    static AntiAliasingMode aam;
    static AntiAliasingPattern aap;
    
    
    
    static CPUAlgebraicSurfaceRenderer asr = new CPUAlgebraicSurfaceRenderer();

    static BufferedImage createBufferedImageFromRGB( ImgBuffer ib )
    {
        int w = ib.width;
        int h = ib.height;

        DirectColorModel colormodel = new DirectColorModel( 24, 0xff0000, 0xff00, 0xff );
        SampleModel sampleModel = colormodel.createCompatibleSampleModel( w, h );
        DataBufferInt data = new DataBufferInt( ib.rgbBuffer, w * h );
        WritableRaster raster = WritableRaster.createWritableRaster( sampleModel, data, new Point( 0, 0 ) );
        return new BufferedImage( colormodel, raster, false, null );
    }
    
    public static BufferedImage flipV( BufferedImage bi )
    {
    	AffineTransform tx = AffineTransform.getScaleInstance(1, -1);
        tx.translate( 0, -bi.getHeight(null) );
        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        return op.filter(bi, null);
    }
    
	/**
	 * @param args
	 */
	public static void main(String[] args) {  
		
    	String jsurf_filename = "";
    	Options options = new Options();
    	
    	options.addOption("s","size", true, "width (and height) of a image (default: " + size + ")");
    	options.addOption("q","quality",true,"quality of the rendering: 0 (low), 1 (medium, default), 2 (high), 3 (extreme)");
    	options.addOption("o","output",true,"output PNG into this file (- means standard output. Use ./- to denote a file literally named -.)");

    	CommandLineParser parser = new PosixParser();
		HelpFormatter formatter = new HelpFormatter();
    	String cmd_line_syntax = "jsurf [options] jsurf_file";
    	String help_header = "jsurf is a renderer for algebraic surfaces. If - is specified as a filename the jsurf file is read from standard input. " +
    			"Use ./- to denote a file literally named -.";
    	String help_footer = "";
    	try
    	{
    		CommandLine cmd = parser.parse( options, args );
    		
    		if( cmd.getArgs().length > 0)
    			jsurf_filename = cmd.getArgs()[ 0 ];
    		else
    		{
    			formatter.printHelp( cmd_line_syntax, help_header, options, help_footer );
    			return;
    		}
    		
    		if( cmd.hasOption( "output" ) ) {
			}
    		
    		if( cmd.hasOption("size") )
    			size = Integer.parseInt( cmd.getOptionValue("size") );
    		
    		int quality = 1;
    		if( cmd.hasOption("quality") )
    			quality = Integer.parseInt( cmd.getOptionValue( "quality" ) );
			switch( quality )
			{
			case 0:
		    	aam = AntiAliasingMode.ADAPTIVE_SUPERSAMPLING;
		    	aap = AntiAliasingPattern.OG_1x1;
		    	break;
			case 2:
		    	aam = AntiAliasingMode.ADAPTIVE_SUPERSAMPLING;
		    	aap = AntiAliasingPattern.OG_4x4;
		    	break;
			case 3:
		    	aam = AntiAliasingMode.SUPERSAMPLING;
		    	aap = AntiAliasingPattern.OG_4x4;
		    	break;
			case 1:
		    	aam = AntiAliasingMode.ADAPTIVE_SUPERSAMPLING;
		    	aap = AntiAliasingPattern.QUINCUNX;
			}
    	}
    	catch( ParseException exp ) {
    	    System.out.println( "Unexpected exception:" + exp.getMessage() );
    	    System.exit( -1 );
    	}
    	catch( NumberFormatException nfe )
    	{
    		formatter.printHelp( cmd_line_syntax, help_header, options, help_footer );
    		System.exit( -1 );
    	}
    	
    	final Properties jsurf = new Properties();
    	try
    	{
    		if( jsurf_filename.equals( "-" ) )
    			jsurf.load( System.in );
    		else
    			jsurf.load( new FileReader( jsurf_filename ) );
    		FileFormat.load( jsurf, asr );	
    	}
    	catch( Exception e ) 
    	{
    		System.err.println( "Unable to read jsurf file " + jsurf_filename  );
    		e.printStackTrace();
    		System.exit( -2 );
    	}
    	
    	asr.setAntiAliasingMode( aam );
    	asr.setAntiAliasingPattern( aap );
    	
    	
    		// display the image in a window 
    	final String window_title = "jsurf: " + jsurf_filename;
    	SwingUtilities.invokeLater( new Runnable() {
    		public void run()
    		{
    			JFrame f = new JFrame( window_title );
    			f.setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );
    			JSurferRenderPanel p = null;
    			try {
    				p = new JSurferRenderPanel(jsurf);
    			} catch (Exception e) {
    				// TODO Auto-generated catch block
    				e.printStackTrace();
    			}
    			
    			f.setContentPane(p);
    			
    			//    				f.getContentPane().add( new JLabel( new ImageIcon( window_image ) ) );
    			f.pack();
    			//    				f.setResizable( false );
    			f.setVisible( true );
    		}
    	});
	}

}


class ImgBuffer
{
    public int[] rgbBuffer;
    public int width;
    public int height;

    public ImgBuffer( int w, int h ) { rgbBuffer = new int[ 3 * w * h ]; width = w; height = h; }
}

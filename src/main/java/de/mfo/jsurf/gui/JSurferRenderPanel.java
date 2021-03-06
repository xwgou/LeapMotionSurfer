/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package de.mfo.jsurf.gui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.event.*;

import javax.swing.*;
import java.awt.image.*;
import javax.vecmath.*;

import com.leapmotion.leap.Controller;

// input/output
import java.net.URL;
import java.util.*;
import java.io.*;

import de.mfo.jsurf.rendering.*;
import de.mfo.jsurf.rendering.cpu.*;
import de.mfo.jsurf.util.*;
import static de.mfo.jsurf.rendering.cpu.CPUAlgebraicSurfaceRenderer.AntiAliasingMode;

import java.util.concurrent.*;

/**
 * This panel displays an algebraic surface in its center. All settings of the used
 * @see{AlgebraicSurfaceRenderer} must be made by the user of this panel.
 * Only the surface an camera transformations are set automatically by this#
 * class. Changing same directly on the @see{AlgebraicSurfaceRenderer} or
 * @see{Camera} does not affect rendering at all.
 * Additionally it keeps the aspect ratio and anti-aliases the image, if there
 * is no user interaction.
 * @author Christian Stussak <christian at knorf.de>
 */
public class JSurferRenderPanel extends JComponent
{
    /**
	 * 
	 */
	private static final long serialVersionUID = 4458431262757214162L;

	class ImgBuffer
    {
        public int[] rgbBuffer;
        public int width;
        public int height;

        public ImgBuffer( int w, int h ) { rgbBuffer = new int[ 3 * w * h ]; width = w; height = h; }
    }


    CPUAlgebraicSurfaceRenderer asr;
    ImgBuffer currentSurfaceImage;
    boolean resizeImageWithComponent;
    boolean renderCoordinatenSystem;
    Dimension renderSize;
    Dimension minLowResRenderSize;
    Dimension maxLowResRenderSize;
    RotateSphericalDragger rsd;
    Matrix4d scale;
    RenderWorker rw;
    
    Controller controller;
    LeapMotionListener lm;

    class RenderWorker extends Thread
    {
        Semaphore semaphore = new Semaphore( 0 );
        boolean finish = false;
        boolean is_drawing_hi_res = false;
        double time_per_pixel = 1000.0;
        final double desired_fps = 15.0;
        boolean skip_hi_res = false;

        public void finish()
        {
            finish = true;
        }

        public void scheduleRepaint()
        {
            // schedule redraw
            semaphore.release();

            // try to ensure, that high resolution drawing is canceled
            if( is_drawing_hi_res )
                JSurferRenderPanel.this.asr.stopDrawing();
        }

        public void stopHighResolutionRendering()
        {
            semaphore.drainPermits(); // remove all currently available permits
            skip_hi_res = true;

            // try to ensure, that current high resolution rendering is canceled
            if( is_drawing_hi_res )
                JSurferRenderPanel.this.asr.stopDrawing();
        }

        @Override
        public void run()
        {
            this.setPriority( Thread.MIN_PRIORITY );
            while( !finish )
            {
                try
                {
                    int available_permits = semaphore.availablePermits();
                    semaphore.acquire( Math.max( 1, available_permits ) ); // wait for new task and grab all permits
                    skip_hi_res = false;
                    long minPixels = JSurferRenderPanel.this.minLowResRenderSize.width * JSurferRenderPanel.this.minLowResRenderSize.height;
                    long maxPixels = JSurferRenderPanel.this.maxLowResRenderSize.width * JSurferRenderPanel.this.maxLowResRenderSize.height;
                    maxPixels = Math.max( 1, Math.min( maxPixels, JSurferRenderPanel.this.getWidth() * JSurferRenderPanel.this.getHeight() ) );
                    minPixels = Math.min( minPixels, maxPixels );
                    long numPixelsAt15FPS = ( long ) ( 1.0 / ( desired_fps * time_per_pixel ) );
                    long pixelsToUse = Math.max( minPixels, Math.min( maxPixels, numPixelsAt15FPS ) );
                    JSurferRenderPanel.this.renderSize = new Dimension( (int) Math.sqrt( pixelsToUse ), (int) Math.sqrt( pixelsToUse ) );

                    // render low res
                    {
                        ImgBuffer ib = draw( renderSize.width, renderSize.height, AntiAliasingMode.ADAPTIVE_SUPERSAMPLING, AntiAliasingPattern.QUINCUNX, true );
                        if( ib != null )
                        {
                            currentSurfaceImage =  ib;
                            JSurferRenderPanel.this.repaint();
                        }
                    }

                    if( semaphore.tryAcquire( 100, TimeUnit.MILLISECONDS ) ) // wait some time, then start with high res drawing
                    {
                        semaphore.release();
                        continue;
                    }
                    else if( skip_hi_res )
                        continue;

                    // render high res, if no new low res rendering is scheduled
                    {
                        is_drawing_hi_res = true;
                        ImgBuffer ib = draw( JSurferRenderPanel.this.getWidth(), JSurferRenderPanel.this.getHeight(), AntiAliasingMode.ADAPTIVE_SUPERSAMPLING, AntiAliasingPattern.OG_4x4, false );
                        if( ib != null )
                        {
                            currentSurfaceImage =  ib;
                            JSurferRenderPanel.this.repaint();
                        }
                        is_drawing_hi_res = false;
                    }

                    if( semaphore.availablePermits() > 0 ) // restart, if user has changes the view
                        continue;
                    else if( skip_hi_res )
                        continue;

                    // render high res with even better quality
                    {
                        //System.out.println( "drawing hi res");
                        is_drawing_hi_res = true;
                        ImgBuffer ib = draw( JSurferRenderPanel.this.getWidth(), JSurferRenderPanel.this.getHeight(), AntiAliasingMode.SUPERSAMPLING, AntiAliasingPattern.OG_4x4, false );
                        if( ib != null )
                        {
                            currentSurfaceImage =  ib;
                            JSurferRenderPanel.this.repaint();
                        }
                        is_drawing_hi_res = false;
                        //System.out.println( "finised hi res");
                    }
                }
                catch( InterruptedException ie )
                {
                }
            }
        }

        public ImgBuffer draw( int width, int height, CPUAlgebraicSurfaceRenderer.AntiAliasingMode aam, AntiAliasingPattern aap )
        {
            return draw( width, height, aam, aap, false );
        }

        public ImgBuffer draw( int width, int height, CPUAlgebraicSurfaceRenderer.AntiAliasingMode aam, AntiAliasingPattern aap, boolean save_fps )
        {
            // create color buffer
            ImgBuffer ib = new ImgBuffer( width, height );

            // do rendering
            Matrix4d rotation = new Matrix4d();
            rotation.invert( rsd.getRotation() );
            Matrix4d id = new Matrix4d();
            id.setIdentity();
            Matrix4d tm = new Matrix4d( rsd.getRotation() );
            tm.mul( scale );
            asr.setTransform( rsd.getRotation() );
            asr.setSurfaceTransform( scale );
            asr.setAntiAliasingMode( aam );
            asr.setAntiAliasingPattern( aap );
            setOptimalCameraDistance( asr.getCamera() );

            try
            {
                long t_start = System.nanoTime();
                asr.draw( ib.rgbBuffer, width, height );
                long t_end = System.nanoTime();
                double fps = 1000000000.0 / ( t_end - t_start );
                System.err.println( fps + "fps at " + width +"x" + height );
                if( save_fps )
                    time_per_pixel = ( ( t_end - t_start ) / 1000000000.0 ) / ( width * height );
                return ib;
            }
            catch( RenderingInterruptedException rie )
            {
                return null;
            }
            catch( Throwable t )
            {
                t.printStackTrace();
                return null;
            }
        }
    }
    
    private void configure() {
    	 MouseAdapter ma = new MouseAdapter(){
             public void mousePressed( MouseEvent me ) { JSurferRenderPanel.this.mousePressed( me ); }
             public void mouseDragged( MouseEvent me ) { JSurferRenderPanel.this.mouseDragged( me ); }
             public void mouseWheelMoved( MouseWheelEvent mwe ) { JSurferRenderPanel.this.scaleSurface ( mwe.getWheelRotation() ); }
         };

         addMouseListener( ma );
         addMouseMotionListener( (MouseMotionListener) ma);
         addMouseWheelListener( (MouseWheelListener) ma);

         KeyAdapter ka = new KeyAdapter() {
             public void keyPressed( KeyEvent e )
             {
                 if( e.getKeyCode() == KeyEvent.VK_DOWN || e.getKeyCode() == KeyEvent.VK_MINUS )
                     scaleSurface( 1 );
                 else if( e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_PLUS )
                     scaleSurface( -1 );
             }
         };
         addKeyListener( ka );

         ComponentAdapter ca = new ComponentAdapter() {
             public void componentResized( ComponentEvent ce ) { JSurferRenderPanel.this.componentResized( ce ); }
         };
         addComponentListener( ca );
         
         // add leapMotion listener
         controller = new Controller();
         lm = new LeapMotionListener(this);
         controller.addListener(lm);
    }
    
    public JSurferRenderPanel(Properties properties) throws Exception
    {
    	renderCoordinatenSystem = false;
        minLowResRenderSize = new Dimension( 150, 150 );
        maxLowResRenderSize = new Dimension( 512, 512 );
        
        resizeImageWithComponent = false;
        
    	asr = new CPUAlgebraicSurfaceRenderer();

    	rsd = new RotateSphericalDragger();
    	scale = new Matrix4d();
    	scale.setIdentity();
        loadFromProperties(properties);
        
        configure();

        setDoubleBuffered( true );
        setFocusable( true );
        
/*
        final JFrame jframe = new JFrame( "One Triangle Swing GLCanvas" );
        jframe.getContentPane().add( glcanvas, BorderLayout.CENTER );
        jframe.setSize( 640, 480 );
        jframe.setVisible( true );
*/
        
        rw = new RenderWorker();
        rw.start();
        currentSurfaceImage = null;
    }
    

    public JSurferRenderPanel()
    {
        renderCoordinatenSystem = false;
        minLowResRenderSize = new Dimension( 150, 150 );
        maxLowResRenderSize = new Dimension( 512, 512 );
        //renderSize = minLowResRenderSize;

        resizeImageWithComponent = false;

        asr = new CPUAlgebraicSurfaceRenderer();

        rsd = new RotateSphericalDragger();
        scale = new Matrix4d();
        scale.setIdentity();
        configure();

        setDoubleBuffered( true );
        setFocusable( true );
/*
        final JFrame jframe = new JFrame( "One Triangle Swing GLCanvas" );
        jframe.getContentPane().add( glcanvas, BorderLayout.CENTER );
        jframe.setSize( 640, 480 );
        jframe.setVisible( true );
*/
        rw = new RenderWorker();
        rw.start();
        currentSurfaceImage = null;
    }

    public AlgebraicSurfaceRenderer getAlgebraicSurfaceRenderer()
    {
        return this.asr;
    }

    public void setResizeImageWithComponent( boolean resize )
    {
        resizeImageWithComponent = resize;
    }

    public boolean getResizeWithComponent()
    {
        return resizeImageWithComponent;
    }

    public void repaintImage()
    {
        scheduleSurfaceRepaint();
    }

    public Dimension getPreferredSize()
    {
        return new Dimension( minLowResRenderSize.width, minLowResRenderSize.height );
    }


    public void setMinLowResRenderSize( Dimension d )
    {
        this.minLowResRenderSize = d;
    }

    public void setMaxLowResRenderSize( Dimension d )
    {
        this.maxLowResRenderSize = d;
    }

    public Dimension getMinLowResRenderSize()
    {
        return this.minLowResRenderSize;
    }

    public Dimension getMaxLowResRenderSize()
    {
        return this.maxLowResRenderSize;
    }

    public Dimension getRenderSize()
    {
        return this.renderSize;
    }

    public void setScale( double scaleFactor )
    {
        if (scaleFactor<-2.0)scaleFactor=-2.0;
        if (scaleFactor>2.0)scaleFactor=2.0;

        scaleFactor= Math.pow( 10, scaleFactor);
        //System.out.println(" scaleFactor: "+scaleFactor);
        scale.setScale( scaleFactor );
    }

    public double getScale()
    {
        //System.out.println("getScale "+this.scale.getScale()+" "+this.scale.m00+" "+(float)Math.log10(this.scale.getScale()));
        return Math.log10(this.scale.getScale());
    }

    public void saveToPNG( java.io.File f, int width, int height )
            throws java.io.IOException
    {
        Dimension oldMinDim = getMinLowResRenderSize();
        Dimension oldMaxDim = getMaxLowResRenderSize();
        setMinLowResRenderSize( new Dimension( width, height ) );
        setMaxLowResRenderSize( new Dimension( width, height ) );
        scheduleSurfaceRepaint();
        try
        {
            saveToPNG( f, (ImgBuffer) rw.draw( width, height, CPUAlgebraicSurfaceRenderer.AntiAliasingMode.ADAPTIVE_SUPERSAMPLING, AntiAliasingPattern.OG_4x4 ) );
        }
        catch( java.util.concurrent.CancellationException ce ) {}
        setMinLowResRenderSize( oldMinDim );
        setMaxLowResRenderSize( oldMaxDim );
        scheduleSurfaceRepaint();
    }
    public void saveString(java.io.File file, java.lang.String string)
            throws java.io.IOException
    {
        java.io.FileWriter writer=new java.io.FileWriter(file ,false);
        writer.write(string);
        writer.flush();
        writer.close();
    }
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

    public void saveToPNG( java.io.File f )
            throws java.io.IOException
    {
        saveToPNG( f, currentSurfaceImage );
    }

    public static void saveToPNG( java.io.File f, ImgBuffer imgbuf )
            throws java.io.IOException
    {
        BufferedImage bufferedImage = createBufferedImageFromRGB( imgbuf );
        AffineTransform tx = AffineTransform.getScaleInstance(1, -1);
        tx.translate(0, -bufferedImage.getHeight(null));
        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        bufferedImage = op.filter(bufferedImage, null);
        javax.imageio.ImageIO.write( bufferedImage, "png", f );
    }

    protected void paintComponent( Graphics g )
    {
        super.paintComponent( g );
        if( g instanceof Graphics2D )
        {
            final Graphics2D g2 = ( Graphics2D ) g;
            g2.setColor( this.asr.getBackgroundColor().get() );

            ImgBuffer tmpImg = currentSurfaceImage;
            if( tmpImg == null || tmpImg.width == 0 || tmpImg.height == 0 )
            {
                g2.fillRect( 0, 0, this.getWidth(), this.getHeight() );
            }
            else
            {
                BufferedImage bi = JSurferRenderPanel.createBufferedImageFromRGB( tmpImg );
                final AffineTransform t = new AffineTransform();
                t.scale( this.getWidth() / (double) bi.getWidth(), -this.getHeight() / (double) bi.getHeight() );
                t.translate( 0, -bi.getHeight() );
                g2.drawImage( bi, new AffineTransformOp( t, AffineTransformOp.TYPE_BILINEAR ), 0, 0 );
            }
        }
        else
        {
            super.paintComponents( g );
            g.drawString( "this component needs a Graphics2D for painting", 2, this.getHeight() - 2 );
        }
    }

    protected void scheduleSurfaceRepaint()
    {
        rw.scheduleRepaint();
    }

    protected static void setOptimalCameraDistance( Camera c )
    {
        float cameraDistance;
        switch( c.getCameraType() )
        {
            case ORTHOGRAPHIC_CAMERA:
                cameraDistance = 1.0f;
                break;
            case PERSPECTIVE_CAMERA:
                cameraDistance = ( float ) ( 1.0 / Math.sin( ( Math.PI / 180.0 ) * ( c.getFoVY() / 2.0 ) ) );
                break;
            default:
                throw new RuntimeException();
        }
        c.lookAt( new Point3d( 0, 0, cameraDistance ), new Point3d( 0, 0, -1 ), new Vector3d( 0, 1, 0 ) );
    }

    protected void componentResized( ComponentEvent ce )
    {
        rsd.setXSpeed( 180.0f / this.getWidth() );
        rsd.setYSpeed( 180.0f / this.getHeight() );
        scheduleSurfaceRepaint();
        repaint();
    }

    protected void mousePressed( MouseEvent me )
    {
        grabFocus();
        rsd.startDrag( me.getPoint() );
    }

    protected void mouseDragged( MouseEvent me )
    {
        rsd.dragTo( me.getPoint() );
        //drawCoordinatenSystem(true);
        scheduleSurfaceRepaint();
    }

    protected void scaleSurface( int units )
    {

        /*Matrix4f tmp = new Matrix4f();
        tmp.setIdentity();
        tmp.setScale( ( float ) Math.pow( 1.0625, units ) );
        scale.mul( tmp );*/

        this.setScale(this.getScale()-units/500.0 );
        //this.setScale(0);
        scheduleSurfaceRepaint();
    }

    public void loadFromString( String s )
            throws Exception
    {
        Properties props = new Properties();
        props.load( new ByteArrayInputStream( s.getBytes() ) );
        loadFromProperties( props );
    }

    public void loadFromFile( URL url )
            throws IOException, Exception
    {
        Properties props = new Properties();
        props.load( url.openStream() );
        loadFromProperties( props );
    }

    public void loadFromProperties( Properties props )
            throws Exception
    {
    	FileFormat.load(props, asr);
    }

    public void saveToFile( URL url )
            throws IOException
    {
        Properties props = new Properties();
        props.setProperty( "surface_equation", asr.getSurfaceFamilyString() );

        Set< String > paramNames = asr.getAllParameterNames();
        for( String paramName : paramNames )
        {
            try
            {
                props.setProperty( "surface_parameter_" + paramName, "" + asr.getParameterValue( paramName ) );
            }
            catch( Exception e ) {}
        }

        asr.getCamera().saveProperties( props, "camera_", "" );
        asr.getFrontMaterial().saveProperties(props, "front_material_", "");
        asr.getBackMaterial().saveProperties(props, "back_material_", "");
        for( int i = 0; i < AlgebraicSurfaceRenderer.MAX_LIGHTS; i++ )
            asr.getLightSource( i ).saveProperties( props, "light_", "_" + i );
        props.setProperty( "background_color", BasicIO.toString( asr.getBackgroundColor() ) );

        props.setProperty( "scale_factor", ""+this.getScale() );
        props.setProperty( "rotation_matrix", BasicIO.toString( rsd.getRotation() ));

        File property_file = new File( url.getFile() );
        props.store( new FileOutputStream( property_file ), "jSurfer surface description" );
    }
    public void drawCoordinatenSystem(boolean b)
    {
        renderCoordinatenSystem=b;
    }
    @SuppressWarnings("deprecation")
	public static void generateGalleryThumbnails( String jsurf_folder, String png_folder )
    {
        JSurferRenderPanel p = new JSurferRenderPanel();
        synchronized( p.asr )
        {
            try
            {
                new File( png_folder ).mkdir();
                JSurferRenderPanel.ImgBuffer ib = p.new ImgBuffer( 120, 120 );

                String[] jsurf_dir_content = new File( jsurf_folder ).list();
                if( jsurf_dir_content == null )
                        System.err.println( new File( jsurf_folder ) + " does not exist or is not a directory" );
                for( String filename : jsurf_dir_content )
                {
                    if( filename.endsWith( ".jsurf" ) )
                    {
                        String key = filename.substring( 0, filename.length() - 6 );
                        File jsurf_file = new File( jsurf_folder + File.separator + filename );
                        File png_file = new File( png_folder + File.separator + key + "_icon.png" );
                        System.out.print( "generating thumbnail for " + jsurf_file + " at " + png_file );
                        p.loadFromFile( jsurf_file.getAbsoluteFile().toURL() );

                        // do rendering
                        p.asr.setTransform( p.rsd.getRotation() );
                        p.asr.setSurfaceTransform( p.scale );
                        p.asr.setAntiAliasingMode( CPUAlgebraicSurfaceRenderer.AntiAliasingMode.ADAPTIVE_SUPERSAMPLING );
                        p.asr.setAntiAliasingPattern( AntiAliasingPattern.RG_2x2 );

                        p.asr.draw( ib.rgbBuffer, ib.width, ib.height );

                        saveToPNG( png_file, ib );
                        System.out.println( " ... done" );
                    }
                }
            }
            catch( Throwable t )
            {
                System.err.println( t );
                t.printStackTrace( System.err );
            }
        }
        System.exit( 0 );
    }
//
//    public static void main( String[]args )
//    {
//        //generateGalleryThumbnails( "./src/de/mfo/jsurfer/gallery", "/home/stussak/Desktop/JFXSurferGalleryThumbnails" );
//        //if( true ) return;
//        JSurferRenderPanel p = new JSurferRenderPanel();
//        //p.setResizeImageWithComponent( true );
//
//        try
//        {
//            p.getAlgebraicSurfaceRenderer().setSurfaceFamily( "x^2+y^2+z^2+2*x*y*z-1" );
//            p.setScale( 1.025 );
//        }
//        catch( Exception e )
//        {
//            e.printStackTrace();
//        }
//
//        JFrame f = new JFrame();
//        f.setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );
//        f.getContentPane().add( p );
//        f.pack();
//        f.setVisible( true );
//    }
}

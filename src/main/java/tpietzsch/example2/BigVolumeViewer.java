package tpietzsch.example2;

import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.tools.InitializeViewerState;
import bdv.tools.ToggleDialogAction;
import bdv.tools.VisibilityAndGroupingDialog;
import bdv.tools.brightness.BrightnessDialog;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.MinMaxGroup;
import bdv.tools.brightness.SetupAssignments;
import bdv.tools.transformation.ManualTransformation;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.state.SourceState;
import bdv.viewer.state.ViewerState;
import com.jogamp.opengl.GL3;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import mpicbg.spim.data.SpimDataException;
import net.imglib2.Interval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.LinAlgHelpers;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.io.yaml.YamlConfigIO;
import tpietzsch.example2.VolumeViewerPanel.RenderData;
import tpietzsch.multires.SpimDataStacks;
import tpietzsch.scene.TexturedUnitCube;

import static bdv.BigDataViewer.initSetups;

public class BigVolumeViewer
{
	// ... BDV ...
	private final VolumeViewerFrame frame;
	private final VolumeViewerPanel viewer;
	private final ManualTransformation manualTransformation;
	private final SetupAssignments setupAssignments;
	private final BrightnessDialog brightnessDialog;
	private final VisibilityAndGroupingDialog activeSourcesDialog;


	public BigVolumeViewer(
			final ArrayList< ConverterSetup > converterSetups,
			final ArrayList< SourceAndConverter< ? > > sources,
			final SpimDataStacks stacks,
			final String windowTitle,
			final VolumeViewerOptions options )
	{
		final InputTriggerConfig keyConfig = getInputTriggerConfig( options );
		options.inputTriggerConfig( keyConfig );

		frame = new VolumeViewerFrame( sources, converterSetups, stacks, this::renderScene, options );
		if ( windowTitle != null )
			frame.setTitle( windowTitle );
		viewer = frame.getViewerPanel();

		for ( final ConverterSetup setup : converterSetups )
			setup.setViewer( viewer );

		manualTransformation = new ManualTransformation( sources );

		setupAssignments = new SetupAssignments( converterSetups, 0, 65535 );
		if ( setupAssignments.getMinMaxGroups().size() > 0 )
		{
			final MinMaxGroup group = setupAssignments.getMinMaxGroups().get( 0 );
			for ( final ConverterSetup setup : setupAssignments.getConverterSetups() )
				setupAssignments.moveSetupToGroup( setup, group );
		}

		brightnessDialog = new BrightnessDialog( frame, setupAssignments );

		activeSourcesDialog = new VisibilityAndGroupingDialog( frame, viewer.getVisibilityAndGrouping() );

		NavigationActions.installActionBindings( frame.getKeybindings(), viewer, keyConfig );
		frame.getDefaultActions().namedAction( new ToggleDialogAction( "toggle brightness dialog", brightnessDialog ), "S" );
		frame.getDefaultActions().namedAction( new ToggleDialogAction( "toggle active sources dialog", activeSourcesDialog ), "F6" );
	}

	// -------------------------------------------------------------------------------------------------------
	// BDV ViewerPanel equivalents

	public void loadSettings( final String xmlFilename ) throws IOException, JDOMException
	{
		final SAXBuilder sax = new SAXBuilder();
		final Document doc = sax.build( xmlFilename );
		final Element root = doc.getRootElement();
		viewer.stateFromXml( root );
		setupAssignments.restoreFromXml( root );
		manualTransformation.restoreFromXml( root );
	}

	public boolean tryLoadSettings( final String xmlFilename )
	{
		if ( xmlFilename.endsWith( ".xml" ) )
		{
			final String settings = xmlFilename.substring( 0, xmlFilename.length() - ".xml".length() ) + ".settings" + ".xml";
			final File proposedSettingsFile = new File( settings );
			if ( proposedSettingsFile.isFile() )
			{
				try
				{
					loadSettings( settings );
					return true;
				}
				catch ( final Exception e )
				{
					e.printStackTrace();
				}
			}
		}
		return false;
	}

	/**
	 * If {@code options} doesn't define a {@link InputTriggerConfig}, try to
	 * load it from files in this order:
	 * <ol>
	 * <li>"bdvkeyconfig.yaml" in the current directory.
	 * <li>".bdv/bdvkeyconfig.yaml" in the user's home directory.
	 * <li>legacy "bigdataviewer.keys.properties" in current directory (will be
	 * also written to "bdvkeyconfig.yaml").
	 * </ol>
	 *
	 * @param options
	 * @return
	 */
	public static InputTriggerConfig getInputTriggerConfig( final VolumeViewerOptions options )
	{
		InputTriggerConfig conf = options.values.getInputTriggerConfig();

		// try "bdvkeyconfig.yaml" in current directory
		if ( conf == null && new File( "bdvkeyconfig.yaml" ).isFile() )
		{
			try
			{
				conf = new InputTriggerConfig( YamlConfigIO.read( "bdvkeyconfig.yaml" ) );
			}
			catch ( final IOException e )
			{}
		}

		// try "~/.bdv/bdvkeyconfig.yaml"
		if ( conf == null )
		{
			final String fn = System.getProperty( "user.home" ) + "/.bdv/bdvkeyconfig.yaml";
			if ( new File( fn ).isFile() )
			{
				try
				{
					conf = new InputTriggerConfig( YamlConfigIO.read( fn ) );
				}
				catch ( final IOException e )
				{}
			}
		}

		if ( conf == null )
		{
			conf = new InputTriggerConfig();
		}

		return conf;
	}

	// -------------------------------------------------------------------------------------------------------
	// ... "pre-existing" scene...

	private final TexturedUnitCube[] cubes = new TexturedUnitCube[]{
			new TexturedUnitCube("imglib2.png" ),
			new TexturedUnitCube("fiji.png" ),
			new TexturedUnitCube("imagej2.png" ),
			new TexturedUnitCube("scijava.png" ),
			new TexturedUnitCube("container.jpg" )
	};
	static class CubeAndTransform {
		final TexturedUnitCube cube;
		final Matrix4f model;
		public CubeAndTransform( final TexturedUnitCube cube, final Matrix4f model )
		{
			this.cube = cube;
			this.model = model;
		}
	}
	private final ArrayList< CubeAndTransform > cubeAndTransforms = new ArrayList<>();

	private void renderScene( final GL3 gl, final RenderData data )
	{
		synchronized ( cubeAndTransforms )
		{
			for ( CubeAndTransform cubeAndTransform : cubeAndTransforms )
			{
				cubeAndTransform.cube.draw( gl, new Matrix4f( data.getPv() ).mul( cubeAndTransform.model ) );
			}
		}
	}

	private Random random = new Random();

	void removeRandomCube()
	{
		synchronized ( cubeAndTransforms )
		{
			if ( !cubeAndTransforms.isEmpty() )
				cubeAndTransforms.remove( random.nextInt( cubeAndTransforms.size() ) );
		}
		viewer.requestRepaint();
	}

	void addRandomCube()
	{
		final AffineTransform3D sourceToWorld = new AffineTransform3D();
		final Interval interval;
		final ViewerState state = viewer.getState();
		final int t = state.getCurrentTimepoint();
		final SourceState< ? > source = state.getSources().get( state.getCurrentSource() );
		source.getSpimSource().getSourceTransform( t, 0, sourceToWorld );
		interval = source.getSpimSource().getSource( t, 0 );

		final double[] zero = new double[ 3 ];
		final double[] tzero = new double[ 3 ];
		for ( int d = 0; d < 3; ++d )
			zero[ d ] = interval.min( d );
		sourceToWorld.apply( zero, tzero );

		final double[] one = new double[ 3 ];
		final double[] tone = new double[ 3 ];
		final double[] size = new double[ 3 ];
		for ( int i = 0; i < 3; ++i )
		{
			for ( int d = 0; d < 3; ++d )
				one[ d ] = d == i ? interval.max( d ) + 1 : interval.min( d );
			sourceToWorld.apply( one, tone );
			LinAlgHelpers.subtract( tone, tzero, tone );
			size[ i ] = LinAlgHelpers.length( tone );
		}
		TexturedUnitCube cube = cubes[ random.nextInt( cubes.length ) ];
		Matrix4f model = new Matrix4f()
				.translation(
						( float ) ( tzero[ 0 ] + random.nextDouble() * size[ 0 ] ),
						( float ) ( tzero[ 1 ] + random.nextDouble() * size[ 1 ] ),
						( float ) ( tzero[ 2 ] + random.nextDouble() * size[ 1 ] ) )
				.scale(
						( float ) ( ( random.nextDouble() + 1 ) * size[ 0 ] * 0.05 )	)
				.rotate(
						( float ) ( random.nextDouble() * Math.PI ),
						new Vector3f( random.nextFloat(), random.nextFloat(), random.nextFloat() ).normalize()
				);

		synchronized ( cubeAndTransforms )
		{
			cubeAndTransforms.add( new CubeAndTransform( cube, model ) );
		}
		viewer.requestRepaint();
	}

	// -------------------------------------------------------------------------------------------------------

	public static void run(
			final String xmlFilename,
			final int windowWidth,
			final int windowHeight,
			final int renderWidth,
			final int renderHeight,
			final int ditherWidth,
			final int numDitherSamples,
			final int cacheBlockSize,
			final int maxCacheSizeInMB,
			final double dCam,
			final double dClip ) throws SpimDataException
	{
		final SpimDataMinimal spimData = new XmlIoSpimDataMinimal().load( xmlFilename );

		final ArrayList< ConverterSetup > converterSetups = new ArrayList<>();
		final ArrayList< SourceAndConverter< ? > > sources = new ArrayList<>();
		initSetups( spimData, converterSetups, sources );
		final SpimDataStacks stacks = new SpimDataStacks( spimData );

		final BigVolumeViewer bvv = new BigVolumeViewer( converterSetups, sources, stacks, new File( xmlFilename ).getName(),
				VolumeViewerOptions.options().
						width( windowWidth ).
						height( windowHeight ).
						renderWidth( renderWidth ).
						renderHeight( renderHeight ).
						ditherWidth( ditherWidth ).
						numDitherSamples( numDitherSamples ).
						cacheBlockSize( cacheBlockSize ).
						maxCacheSizeInMB( maxCacheSizeInMB ).
						dCam( dCam ).
						dClip( dClip ) );

		final VolumeViewerFrame frame = bvv.frame;
		final VolumeViewerPanel viewer = bvv.viewer;

		final AffineTransform3D resetTransform = InitializeViewerState.initTransform( windowWidth, windowHeight, false, viewer.state );
		viewer.getTransformEventHandler().setTransform( resetTransform );
		frame.getDefaultActions().runnableAction( () -> {
			viewer.getTransformEventHandler().setTransform( resetTransform );
		}, "reset transform", "R" );

		frame.getDefaultActions().runnableAction( bvv::addRandomCube, "add random cube", "B" );
		frame.getDefaultActions().runnableAction( bvv::removeRandomCube, "remove random cube", "shift B" );

		if ( ! bvv.tryLoadSettings( xmlFilename ) )
			InitializeViewerState.initBrightness( 0.001, 0.999, viewer.state, bvv.setupAssignments );
		bvv.activeSourcesDialog.update();

		frame.setVisible( true );
	}

	public static void main( final String[] args ) throws SpimDataException
	{
		final String xmlFilename = "/Users/pietzsch/workspace/data/111010_weber_full.xml";
//		final String xmlFilename = "/Users/pietzsch/Desktop/data/TGMM_METTE/Pdu_H2BeGFP_CAAXmCherry_0123_20130312_192018.corrected/dataset_hdf5.xml";
//		final String xmlFilename = "/Users/pietzsch/Desktop/data/MAMUT/MaMuT_demo_dataset/MaMuT_Parhyale_demo.xml";

		final int windowWidth = 640;
		final int windowHeight = 480;
		final int renderWidth = 512	;
		final int renderHeight = 512;
		final int ditherWidth = 8;
		final int numDitherSamples = 8;
		final int cacheBlockSize = 32;
		final int maxCacheSizeInMB = 300;
		final double dCam = 2000;
		final double dClip = 1000;

		run( xmlFilename, windowWidth, windowHeight, renderWidth, renderHeight, ditherWidth, numDitherSamples, cacheBlockSize, maxCacheSizeInMB, dCam, dClip );
	}
}

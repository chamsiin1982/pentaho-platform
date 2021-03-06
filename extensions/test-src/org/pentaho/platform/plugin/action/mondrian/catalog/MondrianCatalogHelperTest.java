/*!
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright (c) 2002-2013 Pentaho Corporation..  All rights reserved.
 */

package org.pentaho.platform.plugin.action.mondrian.catalog;

import mondrian.olap.Util.PropertyList;
import mondrian.spi.DynamicSchemaProcessor;
import org.apache.commons.io.IOUtils;
import org.junit.*;
import org.pentaho.database.model.DatabaseConnection;
import org.pentaho.database.model.IDatabaseConnection;
import org.pentaho.database.service.DatabaseDialectService;
import org.pentaho.database.service.IDatabaseDialectService;
import org.pentaho.platform.api.engine.ICacheManager;
import org.pentaho.platform.api.engine.IPentahoDefinableObjectFactory.Scope;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.IUserRoleListService;
import org.pentaho.platform.api.repository2.unified.IRepositoryFileData;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.util.IPasswordService;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.core.system.StandaloneSession;
import org.pentaho.platform.engine.core.system.SystemSettings;
import org.pentaho.platform.plugin.services.cache.CacheManager;
import org.pentaho.platform.repository2.ClientRepositoryPaths;
import org.pentaho.platform.util.Base64PasswordService;
import org.pentaho.test.platform.engine.core.MicroPlatform;
import org.pentaho.test.platform.plugin.UserRoleMapperTest.TestUserRoleListService;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.pentaho.platform.repository2.unified.UnifiedRepositoryTestUtils.*;

@SuppressWarnings( "nls" )
public class MondrianCatalogHelperTest {

  // ~ Instance fields
  // =================================================================================================

  private static MicroPlatform booter;
  private ICacheManager cacheMgr;
  private static IUnifiedRepository repo;

  @BeforeClass
  public static void setUpClass() throws Exception {
    repo = mock( IUnifiedRepository.class );

    booter = new MicroPlatform( "test-src/solution" );
    booter.define( IPasswordService.class, Base64PasswordService.class, Scope.GLOBAL );
    booter.define( IDatabaseConnection.class, DatabaseConnection.class, Scope.GLOBAL );
    booter.define( IDatabaseDialectService.class, DatabaseDialectService.class, Scope.GLOBAL );
    booter.define( IMondrianCatalogService.class, MondrianCatalogHelper.class, Scope.GLOBAL );
    booter.define( ICacheManager.class, CacheManager.class, Scope.GLOBAL );
    booter.define( IUserRoleListService.class, TestUserRoleListService.class, Scope.GLOBAL );
    booter.defineInstance( IUnifiedRepository.class, repo );
    booter.setSettingsProvider( new SystemSettings() );
    booter.start();
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
  }

  @Before
  public void setUp() throws Exception {
    // Clear up the cache
    cacheMgr = PentahoSystem.getCacheManager( null );
    cacheMgr.clearRegionCache( MondrianCatalogHelper.MONDRIAN_CATALOG_CACHE_REGION );
  }

  @After
  public void tearDown() throws Exception {
    reset( repo );
  }

  @Test
  public void testAddCatalog() throws Exception {
    final String mondrianFolderPath = ClientRepositoryPaths.getEtcFolderPath() + RepositoryFile.SEPARATOR + "mondrian";
    stubGetFolder( repo, mondrianFolderPath );
    stubGetChildren( repo, mondrianFolderPath ); // return no children
    final String steelWheelsFolderPath = mondrianFolderPath + RepositoryFile.SEPARATOR + "SteelWheels";
    stubGetFileDoesNotExist( repo, steelWheelsFolderPath );
    stubCreateFolder( repo, steelWheelsFolderPath );
    final String metadataPath = steelWheelsFolderPath + RepositoryFile.SEPARATOR + "metadata";
    stubCreateFile( repo, metadataPath );

    final String olapFolderPath = ClientRepositoryPaths.getEtcFolderPath() + RepositoryFile.SEPARATOR + "olap-servers";
    stubGetFolder( repo, olapFolderPath );
    stubGetChildren( repo, olapFolderPath ); // return no children

    IPentahoSession session = new StandaloneSession( "admin" );
    MondrianCatalogHelper helper = (MondrianCatalogHelper) PentahoSystem.get( IMondrianCatalogService.class );

    MondrianSchema schema = new MondrianSchema( "SteelWheels", null );
    MondrianCatalog cat =
        new MondrianCatalog( "SteelWheels", "Provider=mondrian;DataSource=SampleData;",
            "solution:test/charts/steelwheels.mondrian.xml", schema );
    File file = new File( "test-src/solution/test/charts/steelwheels.mondrian.xml" );
    String mondrianSchema = IOUtils.toString( new FileInputStream( file ) );
    session.setAttribute( "MONDRIAN_SCHEMA_XML_CONTENT", mondrianSchema );
    helper.addCatalog( cat, false, session );

    verify( repo ).createFile(
        eq( makeIdObject( steelWheelsFolderPath ) ),
        argThat( isLikeFile( makeFileObject( metadataPath ) ) ),
        argThat( hasData( pathPropertyPair( "/catalog/definition", "mondrian:/" + cat.getName() ), pathPropertyPair(
            "/catalog/datasourceInfo", cat.getDataSourceInfo() ) ) ), anyString()
    );

    verify( repo ).createFile( eq( makeIdObject( steelWheelsFolderPath ) ),
        argThat( isLikeFile( makeFileObject( steelWheelsFolderPath + RepositoryFile.SEPARATOR + "schema.xml" ) ) ),
        any( IRepositoryFileData.class ), anyString() );
  }

  @Test
  public void testImportSchema() throws Exception {

    String TMP_FILE_PATH = File.separatorChar + "test" + File.separatorChar + "analysis" + File.separatorChar;
    String uploadDir = PentahoSystem.getApplicationContext().getSolutionPath( TMP_FILE_PATH );
    File mondrianFile = new File( uploadDir + File.separatorChar + "SampleData.mondrian.xml" );

    final String mondrianFolderPath = ClientRepositoryPaths.getEtcFolderPath() + RepositoryFile.SEPARATOR + "mondrian";
    final String sampleDataFolderPath = mondrianFolderPath + RepositoryFile.SEPARATOR + "SampleData";
    final String metadataPath = sampleDataFolderPath + RepositoryFile.SEPARATOR + "metadata";

    stubGetFolder( repo, mondrianFolderPath );
    stubGetChildren( repo, mondrianFolderPath ); // return no children

    stubGetFileDoesNotExist( repo, sampleDataFolderPath );
    stubCreateFolder( repo, sampleDataFolderPath );

    stubCreateFile( repo, metadataPath );

    final String olapFolderPath = ClientRepositoryPaths.getEtcFolderPath() + RepositoryFile.SEPARATOR + "olap-servers";
    stubGetFolder( repo, olapFolderPath );
    stubGetChildren( repo, olapFolderPath ); // return no children

    MondrianCatalogHelper helper = (MondrianCatalogHelper) PentahoSystem.get( IMondrianCatalogService.class );
    helper.importSchema( mondrianFile, "SampleData", "" );

    verify( repo ).createFile(
        eq( makeIdObject( sampleDataFolderPath ) ),
        argThat( isLikeFile( makeFileObject( metadataPath ) ) ),
        argThat( hasData( pathPropertyPair( "/catalog/definition", "mondrian:/" + "SampleData" ), pathPropertyPair(
            "/catalog/datasourceInfo", "Provider=mondrian;DataSource=SampleData" ) ) ), anyString()
    );
    verify( repo ).createFile( eq( makeIdObject( sampleDataFolderPath ) ),
        argThat( isLikeFile( makeFileObject( sampleDataFolderPath + RepositoryFile.SEPARATOR + "schema.xml" ) ) ),
        any( IRepositoryFileData.class ), anyString() );
  }

  @Test
  public void testListCatalog() throws Exception {
    File file1 = new File( "test-src/solution/test/charts/steelwheels.mondrian.xml" );
    String mondrianSchema1 = IOUtils.toString( new FileInputStream( file1 ) );
    File file2 = new File( "test-src/solution/samples/reporting/SampleData.mondrian.xml" );
    String mondrianSchema2 = IOUtils.toString( new FileInputStream( file2 ) );

    final String mondrianFolderPath = ClientRepositoryPaths.getEtcFolderPath() + RepositoryFile.SEPARATOR + "mondrian";
    stubGetFolder( repo, mondrianFolderPath );
    stubGetChildren( repo, mondrianFolderPath, "SampleData/", "SteelWheels/" ); // return two child folders

    final String olapFolderPath = ClientRepositoryPaths.getEtcFolderPath() + RepositoryFile.SEPARATOR + "olap-servers";
    stubGetFolder( repo, olapFolderPath );
    stubGetChildren( repo, olapFolderPath ); // return no children

    final String sampleDataFolderPath = mondrianFolderPath + RepositoryFile.SEPARATOR + "SampleData";
    final String sampleDataMetadataPath = sampleDataFolderPath + RepositoryFile.SEPARATOR + "metadata";
    final String sampleDataSchemaPath = sampleDataFolderPath + RepositoryFile.SEPARATOR + "schema.xml";
    stubGetFile( repo, sampleDataMetadataPath );
    stubGetData( repo, sampleDataMetadataPath, "catalog", pathPropertyPair( "/catalog/definition",
        "mondrian:/SampleData" ), pathPropertyPair( "/catalog/datasourceInfo",
        "Provider=mondrian;DataSource=SampleData;" ) );
    stubGetFile( repo, sampleDataSchemaPath );
    stubGetData( repo, sampleDataSchemaPath, mondrianSchema2 );

    final String steelWheelsFolderPath = mondrianFolderPath + RepositoryFile.SEPARATOR + "SteelWheels";
    final String steelWheelsMetadataPath = steelWheelsFolderPath + RepositoryFile.SEPARATOR + "metadata";
    final String steelWheelsSchemaPath = steelWheelsFolderPath + RepositoryFile.SEPARATOR + "schema.xml";
    stubGetFile( repo, steelWheelsMetadataPath );
    stubGetData( repo, steelWheelsMetadataPath, "catalog", pathPropertyPair( "/catalog/definition",
        "mondrian:/SteelWheels" ), pathPropertyPair( "/catalog/datasourceInfo",
        "Provider=mondrian;DataSource=SteelWheels;" ) );
    stubGetFile( repo, steelWheelsSchemaPath );
    stubGetData( repo, steelWheelsSchemaPath, mondrianSchema1 );

    IPentahoSession session = new StandaloneSession( "admin" );
    MondrianCatalogHelper helper = (MondrianCatalogHelper) PentahoSystem.get( IMondrianCatalogService.class );

    List<MondrianCatalog> cats = helper.listCatalogs( session, false );
    Assert.assertEquals( 2, cats.size() );
  }

  @Test
  public void testRemoveCatalog() throws Exception {
    File file1 = new File( "test-src/solution/test/charts/steelwheels.mondrian.xml" );
    String mondrianSchema1 = IOUtils.toString( new FileInputStream( file1 ) );

    final String mondrianFolderPath = ClientRepositoryPaths.getEtcFolderPath() + RepositoryFile.SEPARATOR + "mondrian";
    stubGetFolder( repo, mondrianFolderPath );
    stubGetChildren( repo, mondrianFolderPath, "SteelWheels/" );

    final String steelWheelsFolderPath = mondrianFolderPath + RepositoryFile.SEPARATOR + "SteelWheels";
    final String steelWheelsMetadataPath = steelWheelsFolderPath + RepositoryFile.SEPARATOR + "metadata";
    final String steelWheelsSchemaPath = steelWheelsFolderPath + RepositoryFile.SEPARATOR + "schema.xml";
    stubGetFile( repo, steelWheelsMetadataPath );
    stubGetData( repo, steelWheelsMetadataPath, "catalog", pathPropertyPair( "/catalog/definition",
        "mondrian:/SteelWheels" ), pathPropertyPair( "/catalog/datasourceInfo",
        "Provider=mondrian;DataSource=SteelWheels;" ) );
    stubGetFile( repo, steelWheelsSchemaPath );
    stubGetData( repo, steelWheelsSchemaPath, mondrianSchema1 );

    stubGetFolder( repo, steelWheelsFolderPath );

    IPentahoSession session = new StandaloneSession( "admin" );
    MondrianCatalogHelper helper = (MondrianCatalogHelper) PentahoSystem.get( IMondrianCatalogService.class );

    helper.removeCatalog( "mondrian:/SteelWheels", session );

    verify( repo ).deleteFile( eq( makeIdObject( steelWheelsFolderPath ) ), eq( true ), anyString() );
  }

  @Test
  public void testDspApplied_WhenDataSourceInfoContainsDynamicSchemaProcessorParameter() throws Exception {
    final String replaceTemplate = "REPLACE_TEMPLATE";
    final String
        DATA_SOURCE_INFO_WITH_DSP =
        "DynamicSchemaProcessor=org.pentaho.platform.plugin.action.mondrian.catalog.MondrianCatalogHelperTest$DynamicDSPTest";
    final String SCHEMA_MOCK = "Test Schema Mock " + replaceTemplate;

    MondrianCatalogHelper helper = (MondrianCatalogHelper) PentahoSystem.get( IMondrianCatalogService.class );
    MondrianCatalogHelper helperSpy = spy( helper );
    IPentahoSession session = new StandaloneSession( "admin" );
    String schema = helperSpy.applyDSP( session, DATA_SOURCE_INFO_WITH_DSP, SCHEMA_MOCK );
    assertNotNull( schema );
    assertTrue( schema.contains( "REPLACE_TOKEN" ) );
    verify( helperSpy, never() ).docAtUrlToString( anyString(), any( IPentahoSession.class ) );

  }

  @Test
  public void testNoDspApplied_WhenNoDynamicSchemaProcessorParameterInDataSourceInfo() throws Exception {
    final String replaceTemplate = "REPLACE_TEMPLATE";
    final String DATA_SOURCE_INFO_WITHOUT_DSP = "DataSource=TestDataSource";
    final String SCHEMA_MOCK = "Test Schema Mock " + replaceTemplate;

    MondrianCatalogHelper helper = (MondrianCatalogHelper) PentahoSystem.get( IMondrianCatalogService.class );
    MondrianCatalogHelper helperSpy = spy( helper );
    doReturn( SCHEMA_MOCK ).when( helperSpy ).docAtUrlToString( anyString(), any( IPentahoSession.class ) );

    IPentahoSession session = new StandaloneSession( "admin" );
    String schema = helperSpy.applyDSP( session, DATA_SOURCE_INFO_WITHOUT_DSP, SCHEMA_MOCK );
    assertNotNull( schema );
    verify( helperSpy ).docAtUrlToString( anyString(), any( IPentahoSession.class ) );
    assertFalse( schema.contains( "REPLACE_TOKEN" ) );

  }

  public static class DynamicDSPTest implements DynamicSchemaProcessor {

    @Override
    public String processSchema( String s, PropertyList propertylist ) throws Exception {
      return s.replaceAll( "REPLACE_TEMPLATE", "REPLACE_TOKEN" );
    }

  }

  @Test
  public void testGenerateInMemoryDatasourcesXml_NullEtcMondrianFolder() throws Exception {
    MondrianCatalogHelper helperMock = mock( MondrianCatalogHelper.class );
    IUnifiedRepository unifiedRepositoryMock = mock( IUnifiedRepository.class );

    doReturn( null ).when( unifiedRepositoryMock ).getFile( any( String.class ) );
    doCallRealMethod().when( helperMock ).generateInMemoryDatasourcesXml( any( IUnifiedRepository.class ) );

    String result = helperMock.generateInMemoryDatasourcesXml( unifiedRepositoryMock );
    assertNull( result, null );
  }

  // The next block of 3 tests is for MONDRIAN-2229 issue
  @Test
  public void testGenerateInMemoryDatasourcesXml_DataSourceNameProviderUsingLegacyDbName() throws Exception {
    String result = prepareResultForMondrian2229Tests( true );
    assertThat( result, containsString( "<DataSourceName>Provider=Mondrian</DataSourceName>" ) );
  }

  @Test
  public void testGenerateInMemoryDatasourcesXml_DataSourceNameProviderNotUsingLegacyDbName() throws Exception {
    String result = prepareResultForMondrian2229Tests( false );
    assertThat( result, containsString( "<DataSourceName>Pentaho Mondrian</DataSourceName>" ) );
  }

  @Test
  public void testGenerateInMemoryDatasourcesXml_DataSourceInfoProvider() throws Exception {
    String result = prepareResultForMondrian2229Tests( true );
    assertThat( result, containsString( "<DataSourceInfo>Provider=Mondrian</DataSourceInfo>" ) );
  }

  private String prepareResultForMondrian2229Tests( boolean isUseLegacyDbName ) {
    MondrianCatalogHelper helper = new MondrianCatalogHelper( isUseLegacyDbName );
    MondrianCatalogHelper helperSpy = spy( helper );

    IUnifiedRepository unifiedRepositoryMock = mock( IUnifiedRepository.class );
    RepositoryFile repositoryFileMock = mock( RepositoryFile.class );

    when( unifiedRepositoryMock.getFile( any( String.class ) ) ).thenReturn( repositoryFileMock );

    String contextPathStub = "Stub";
    doReturn( contextPathStub ).when( helperSpy ).contextPathFromRequestContextHolder();

    doNothing().when( helperSpy )
        .appendCatalogsSection( any( IUnifiedRepository.class ), anyString(), any( RepositoryFile.class ),
            any( StringBuffer.class ) );

    String result = helperSpy.generateInMemoryDatasourcesXml( unifiedRepositoryMock );

    return result;
  }
}

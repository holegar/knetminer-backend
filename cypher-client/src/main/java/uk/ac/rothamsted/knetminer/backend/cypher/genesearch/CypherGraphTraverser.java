package uk.ac.rothamsted.knetminer.backend.cypher.genesearch;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import org.neo4j.driver.v1.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;

import net.sourceforge.ondex.algorithm.graphquery.AbstractGraphTraverser;
import net.sourceforge.ondex.algorithm.graphquery.FilterPaths;
import net.sourceforge.ondex.algorithm.graphquery.nodepath.EvidencePathNode;
import net.sourceforge.ondex.core.ONDEXConcept;
import net.sourceforge.ondex.core.ONDEXGraph;
import uk.ac.ebi.utils.exceptions.ExceptionUtils;
import uk.ac.ebi.utils.exceptions.UncheckedFileNotFoundException;
import uk.ac.rothamsted.knetminer.backend.cypher.CypherClient;
import uk.ac.rothamsted.knetminer.backend.cypher.genesearch.helpers.CyTraverserPerformanceTracker;
import uk.ac.rothamsted.knetminer.backend.cypher.genesearch.helpers.PathQueryProcessor;

/**
 * <p>A {@link AbstractGraphTraverser graph traverser} based on Cypher queries against a property graph database
 * storing a BioKNO-based model of an Ondex/Knetminer graph. Currently the backend datbase is based on Neo4j.</p>
 * 
 * <p>This traverser expects the following in {@link #getOptions()}:
 * <ul>
 * 	<li>{@link #CFGOPT_PATH} set to a proper Spring config file.</li>
 * </ul>
 * 
 * Usually the above params are properly set by {@code rres.knetminer.datasource.ondexlocal.OndexServiceProvider}. 
 * </p>
 *
 * @author brandizi
 * <dl><dt>Date:</dt><dd>30 Jan 2019</dd></dl>
 *
 */
public class CypherGraphTraverser extends AbstractGraphTraverser
{
	/**
	 * <p>We use a Spring-based configuration file to read Neo4j configuration, which also has to declare the list or the
	 * folder where .cypher query files are stored.</p> 
	 * 
	 * <p>Each query select a path departing from a gene passed as parameter, as it is specified by {@link CypherClient}.</p>
	 * 
	 * <p>See tests for examples of the kind of file expected here. Default value for this option is {@code "backend/config.xml"}.</p>
	 * 
	 */
	public static final String CFGOPT_PATH = "knetminer.backend.configPath";

	// Made private so we can use it in tests.
	protected static AbstractApplicationContext springContext;
		
	private final Logger log = LoggerFactory.getLogger ( this.getClass () );

	
	public CypherGraphTraverser () {
	}
	
	private void init ()
	{
		// Double-check lazy init (https://www.geeksforgeeks.org/java-singleton-design-pattern-practices-examples/)
		if ( springContext != null ) return;
		
		synchronized ( CypherGraphTraverser.class )
		{
			if ( springContext != null ) return;
			
			String cfgPath = this.getOption ( CFGOPT_PATH, "backend/config.xml" );
			File cfgFile = new File ( cfgPath );
			
			if ( !cfgFile.exists () ) ExceptionUtils.throwEx ( 
				UncheckedFileNotFoundException.class,
				"Backend configuration file '%s' not found, please set %s correctly",
				cfgFile.getAbsolutePath (),
				CFGOPT_PATH
			);
			
			String furl = "file:///" + cfgFile.getAbsolutePath ();
			log.info ( "Configuring {} from <{}>", this.getClass ().getCanonicalName (), furl );
			springContext = new FileSystemXmlApplicationContext ( furl );
			springContext.registerShutdownHook ();
			log.info ( "{} configured", this.getClass ().getCanonicalName () );
		}		
	}
	
	/**
	 * Uses queries in the {@link #springContext} bean named {@code semanticMotifsQueries}. Each query must fulfil certain 
	 * requirement:
	 * 
	 * <ul>
	 * 	<li>The query must return a path as first projected result (see {@link CypherClient#findPathIris(String, Value)}</li>
	 * 	<li>Every returned path must match an Ondex gene concept as first node, followed by Cypher entities corresponding
	 * to Ondex concept/relation pairs</li>
	 * 	<li>The first matching node of each query must receive a {@code startQuery} parameter: 
	 * {@code MATCH path = (g1:Gene{ iri: $startIri }) ...}. See the tests in this hereby project for details.</li>
	 *  <li>Each returned Cypher node/relation must carry an {@code iri} property, coherent with the parameter {@code graph}.</li>
	 * </ul>
	 * 
	 */
	@Override
	@SuppressWarnings ( { "rawtypes" } )
	public List<EvidencePathNode> traverseGraph ( 
		ONDEXGraph graph, ONDEXConcept concept, FilterPaths<EvidencePathNode> filter
	)
	{
		Map<ONDEXConcept, List<EvidencePathNode>> result = 
			this.traverseGraph ( graph, Collections.singleton ( concept ), filter );
		
		return Optional.ofNullable ( result.get ( concept ) )
			.orElse ( new ArrayList<> () );
	}

		
	/**
	 * Wraps the default implementation to enable to track query performance, via {@link CyTraverserPerformanceTracker}. 
	 */
	@Override
	@SuppressWarnings ( { "rawtypes", "static-access" } )
	public Map<ONDEXConcept, List<EvidencePathNode>> traverseGraph ( 
		ONDEXGraph graph, Set<ONDEXConcept> concepts, FilterPaths<EvidencePathNode> filter )
	{
		init ();

		log.info ( "Graph Traverser, beginning parallel traversing of {} concept(s)", concepts.size () );
		
		PathQueryProcessor queryProcessor = this.springContext.getBean ( PathQueryProcessor.class );
		Map<ONDEXConcept, List<EvidencePathNode>> result = queryProcessor.process ( graph, concepts );

		if ( filter == null ) return result;
		
		result.entrySet ()
			.parallelStream ()
			.map ( Entry::getValue )
			.forEach ( paths ->	paths.retainAll ( filter.filterPaths ( paths ) ) );
		
		return result;
	}

	@Override
	public void setOption ( String key, Object value )
	{
		super.setOption ( key, value );
		if ( !"performanceReportFrequency".equals ( key ) ) return;
		springContext.getBean ( CyTraverserPerformanceTracker.class ).setReportFrequency ( (int) value );
	}
}

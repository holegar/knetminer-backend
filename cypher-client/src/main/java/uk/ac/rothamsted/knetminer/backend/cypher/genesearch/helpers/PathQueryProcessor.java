package uk.ac.rothamsted.knetminer.backend.cypher.genesearch.helpers;

import static java.lang.Math.ceil;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import net.sourceforge.ondex.algorithm.graphquery.nodepath.EvidencePathNode;
import net.sourceforge.ondex.core.ONDEXConcept;
import net.sourceforge.ondex.core.ONDEXGraph;
import uk.ac.ebi.utils.runcontrol.PercentProgressLogger;
import uk.ac.ebi.utils.threading.batchproc.BatchProcessor;
import uk.ac.rothamsted.knetminer.backend.cypher.genesearch.CypherGraphTraverser;

/**
 * An helper for {@link CypherGraphTraverser}, which manages {@link SinglePathQueryProcessor}-s, by dispatching the 
 * starting gene list received by 
 * {@link CypherGraphTraverser#traverseGraph(ONDEXGraph, java.util.Set, net.sourceforge.ondex.algorithm.graphquery.FilterPaths)}
 * one {@link SinglePathQueryProcessor} per query configured in {@link #semanticMotifsQueries} (via Spring).
 * 
 * The query-specific path processors are cached in this class, in order to avoid performance problems.
 *
 * @author brandizi
 * <dl><dt>Date:</dt><dd>28 Nov 2019</dd></dl>
 *
 */
@Component
public class PathQueryProcessor implements ApplicationContextAware
{
	// Protected allows inner classes to access without synthetic methods
	protected ApplicationContext springContext;
	
	@Resource( name = "semanticMotifsQueries" )
	private List<String> semanticMotifsQueries; 

	@Autowired
	private CyTraverserPerformanceTracker cyTraverserPerformanceTracker;

	@Autowired ( required = false) @Qualifier ( "queryBatchSize" ) 
	private long queryBatchSize = SinglePathQueryProcessor.DEFAULT_QUERY_BATCH_SIZE;

	
	private LoadingCache<String, SinglePathQueryProcessor> processorCache = 
		CacheBuilder.newBuilder ()
		.maximumSize ( 1000 )
		.build ( new CacheLoader<String, SinglePathQueryProcessor> ()
		{
			@Override
			public SinglePathQueryProcessor load ( String pathQuery )
			{
				SinglePathQueryProcessor result = springContext.getBean ( SinglePathQueryProcessor.class );
				result.setPathQuery ( pathQuery );
				return result;
			} 
			
		});
	
	private Logger log = LoggerFactory.getLogger ( this.getClass () );
	
	public PathQueryProcessor () {
	}
	
	/**
	 * This is the entry point used by 
	 * {@link CypherGraphTraverser#traverseGraph(ONDEXGraph, java.util.Set, net.sourceforge.ondex.algorithm.graphquery.FilterPaths)}.
	 */
	@SuppressWarnings ( { "rawtypes" } )
	public Map<ONDEXConcept, List<EvidencePathNode>> process ( ONDEXGraph graph, Collection<ONDEXConcept> concepts )
	{
		Map<ONDEXConcept, List<EvidencePathNode>> result = Collections.synchronizedMap ( new HashMap<> () );
		
		this.cyTraverserPerformanceTracker.reset ();
				
		PercentProgressLogger queryProgressLogger = new PercentProgressLogger ( 
			"{}% of graph traversing queries processed",
			(long) ceil ( 1.0 * concepts.size () / this.queryBatchSize ) * semanticMotifsQueries.size (),
			10
		);
		
		this.semanticMotifsQueries.parallelStream ()
			.forEach ( query -> 
			{
				SinglePathQueryProcessor thisQueryProc = this.processorCache.getUnchecked ( query );
				thisQueryProc.process ( graph, concepts, result, queryProgressLogger ); 
		});
		
		this.cyTraverserPerformanceTracker.logStats ();
		
		return result;
	}
	
	
	@Override
	public void setApplicationContext ( ApplicationContext applicationContext ) throws BeansException
	{
		this.springContext = applicationContext;
	}	
}

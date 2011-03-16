package org.weceem.services

import java.util.concurrent.ConcurrentHashMap

import org.weceem.content.WcmSpace
import org.weceem.content.WcmContent
import org.weceem.content.WcmTemplate

class WcmContentDependencyService {
    
    static transactional = true

    def grailsApplication
    
    /* We populate this ourselves to work around circular dependencies */
    @Lazy
    def wcmContentRepositoryService = { 
        def s = grailsApplication.mainContext.wcmContentRepositoryService
        return s
    }()

    Map contentDependencyInfo = new ConcurrentHashMap()

    void reset() {
        contentDependencyInfo.clear()
        
        reload()
    }

    void reload() {
        // Find all nodes with contentDependencies
        List<Class> classesWithDeps = grailsApplication.domainClasses.findAll { d -> d.clazz.metaClass.hasProperty(d, 'contentDependencies') }
        if (log.debugEnabled) {
            log.debug "Content classes with contentDependencies: ${classesWithDeps*.clazz}"
        }
        // Find all nodes with templates
        List<Class> classesWithTemplates = grailsApplication.domainClasses.findAll { d -> d.clazz.metaClass.hasProperty(d, 'template') }
        if (log.debugEnabled) {
            log.debug "Content classes with template: ${classesWithTemplates*.clazz}"
        }
        (classesWithDeps + classesWithTemplates).unique().clazz.each { dc ->
            if (log.debugEnabled) {
                log.debug "Loading content instances for ${dc} to load dependency info..."
            }
            dc.list().each { content ->
                if (log.debugEnabled) {
                    log.debug "Content instance ${content.absoluteURI} dependency info being loaded..."
                }
                updateDependencyInfoFor(content)
            }
        }
    }
    
    /**
     * Get the list of string node paths that the specified node explicitly depends on, as well as any special
     * implicit dependencies e.g. Template. This does not recurse, it gather the info for this node only, unless
     * you pass in "true" for "recurse"
     *
     */
    List<String> getDependencyPathsOf(WcmContent content, Boolean recurse = false) {
        if (recurse) {
            HashSet<String> results = []
            HashSet<String> visitedNodes = []
            
            recurseDependencyPathsOf(content, results, visitedNodes)
            return results as List<WcmContent>
        } else {
            return extractDependencyPathsOf(content) as List<WcmContent>
        }
    }
    
    void recurseDependencyPathsOf(WcmContent content, Set<String> results, Set<String> alreadyVisited) {
        def contentURI = content.absoluteURI
        alreadyVisited << contentURI

        def deps = extractDependencyPathsOf(content)
        deps.each {
            if (!alreadyVisited.contains(it)) {
                results << it
            }
        }
        
        println "Before loop, results: $results - already visited ${alreadyVisited}"
        deps.each { d ->
            def nodes = resolveDependencyPathToNodes(d, content.space)
            nodes.each { n ->
                // Prevent stackoverflow
                def nURI = n.absoluteURI
                println "Results: ${results} - node URI ${nURI}, content URI ${contentURI}"
                if (!alreadyVisited.contains(nURI)) {
                    recurseDependencyPathsOf( n, results, alreadyVisited)
                }
            }
        }
    }
    
    protected List<String> extractDependencyPathsOf(WcmContent content) {
        WcmTemplate template = wcmContentRepositoryService.getTemplateForContent(content)
        // A template is an implicit dependency for the node, any changes to the template or its deps
        // means we have to change too.
        def results = template ? [template.absoluteURI] : []
    
        if (content.metaClass.hasProperty(content, 'contentDependencies')) {
            def deps = content.contentDependencies?.split(',')*.trim()
            if (deps) {
                results.addAll(deps)
            }
        }
        
        return results
    }
    
    List<WcmContent> getDependenciesOf(WcmContent content) {
        println "IN GDO for ${content.absoluteURI}"
        List l = []
        gatherDependenciesOf(content, l)
        println "Final getDeps for ${content.absoluteURI}: $l"
        // Remove us
        l.removeAll(content)        
        return l
    }
    
    protected List<WcmContent> resolveDependencyPathToNodes(String path, WcmSpace space) {
        def u = path.trim()
        if (u.endsWith('/**')) {
            def c = wcmContentRepositoryService.findContentForPath(u - '/**', space) 
            if (c?.content) {
                return wcmContentRepositoryService.findDescendents(c.content)
            } else {
                if (log.debugEnabled) {
                    log.debug "Attempt to resolve dependencies ${path} which describes no nodes"
                }
            }
        } else {
            def c = wcmContentRepositoryService.findContentForPath(u, space) 
            if (c?.content) {
                return [c.content]
            }
        }
        return []
    }

    protected void gatherDependenciesOf(WcmContent content, List results) {
        println "IN GDO with ${content.absoluteURI}, ${results}"
        println "Entering GDO Results hash: ${System.identityHashCode(results)}"
        
        def deps = getDependencyPathsOf(content)
        if (deps) {
            deps.each { uri -> 
                def nodes = resolveDependencyPathToNodes(uri, content.space)

                // Filter out results we already have
                def localResults = nodes.findAll { n -> 
                    !results.find { c -> 
                        c.ident() == n.ident()
                    }
                }
                // De-dupe any to prevent extra work / loops
                localResults = localResults.unique()
                
                results.addAll( localResults)
                println "ADDED TO RESULTS: ${localResults*.absoluteURI} - RESULTS NOW: ${results*.absoluteURI}"

                println "Results hash: ${System.identityHashCode(results)}"
                localResults.each { n ->
                    gatherDependenciesOf(n, results)
                    println "In recurse loop results hash: ${System.identityHashCode(results)}"
                }

                println "RESULTS AFTER RECURSE: ${results*.absoluteURI}"
            }
            if (log.debugEnabled) {
                log.debug "Gathered dependencies of ${content.absoluteURI} - results ${results*.absoluteURI}"
            }
        }
    }
    
    def removeDependencyInfoFor(WcmContent content) {
        def id = content.ident()
        synchronized (contentDependencyInfo) {
            def keys = contentDependencyInfo.keySet()
            keys.each { k ->
                contentDependencyInfo[k] = contentDependencyInfo[k].findAll { n -> n != id }
            }
        }
    }
    
    def updateDependencyInfoFor(WcmContent content) {
        if (log.debugEnabled) {
            log.debug "Updating dependency info for: ${content.absoluteURI}"
        }
        removeDependencyInfoFor(content)
        def deps = getDependencyPathsOf(content, true)
        
        println "Deps info before: ${contentDependencyInfo}"
        def dependerId = content.ident()
        deps.each { uri ->
            if (log.debugEnabled) {
                log.debug "Storing dependency: ${content.absoluteURI} depends on $uri"
            }
            // We treat /** and normal uris the same
            def depsList = contentDependencyInfo[uri]
            if (depsList == null) {
                depsList = []
                contentDependencyInfo[uri] = depsList
            }
            if (!depsList.contains(dependerId)) {
                depsList << dependerId
            }
        }
    }
    
    void dumpDependencyInfo(boolean stdout = false) {
        def out = stdout ? { println it } : { log.debug it }
        out "Content dependencies:" 
        contentDependencyInfo.each { k, v ->
            def ns = v.collect { id -> 
                def c = WcmContent.get(id)
                return c ? c.absoluteURI : '?not found?'
            }
            out "$k --- is dependency of nodes with ids ---> ${ns}"
        }
    }
    
    /** 
     * Get a flattened list of all the content nodes dependent on "content"
     * This includes implicit dependencies e.g. inherited templates on deeply nested nodes
     */
    def getContentDependentOn(WcmContent content) {
        /*
        
        index
        products
        blog/**
        faq/**
        widget/
        widget/news
        
        For widget/news >>> deps blog/**
        
        So /blog or /blog/xxxx or /blog/xxxx/comment-4 would return widget/news
        
        blog/** -> [widget/news, templates/homepage]
        blog/ -> [widget/news, templates/homepage]
        // imaginary entry/..
        blog -> []
        
        */
        def u = content.absoluteURI
        def dependents = contentDependencyInfo[u]
        if (dependents == null) {
            dependents = []
        }
        
        // go up parent uris and check for /** at any depth
        // dependents of X = sum of all explicit and ancestral dependents on X
        if (u.indexOf('/') >= 0) {
            def lastSlash = u.lastIndexOf('/')
            if (lastSlash > 0) {
                def deps = contentDependencyInfo[u[0..lastSlash]+'**']
                if (deps) {
                    dependents.addAll(deps)
                }
            }
        }
        if (dependents) {
            // Make sure we are not circular
            dependents.remove(content.ident())
            return WcmContent.getAll(dependents)
        } else return []
    }
       
}

class DependencyIterator {
    def results = []


}
package org.lenskit.eval.traintest;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import groovy.lang.Closure;
import org.grouplens.grapht.Component;
import org.grouplens.grapht.Dependency;
import org.grouplens.grapht.graph.MergePool;
import org.grouplens.grapht.util.ClassLoaders;
import org.grouplens.lenskit.config.ConfigHelpers;
import org.grouplens.lenskit.core.LenskitConfiguration;
import org.grouplens.lenskit.util.io.CompressionMode;
import org.grouplens.lenskit.util.table.Table;
import org.grouplens.lenskit.util.table.TableBuilder;
import org.grouplens.lenskit.util.table.TableLayout;
import org.grouplens.lenskit.util.table.TableLayoutBuilder;
import org.grouplens.lenskit.util.table.writer.CSVWriter;
import org.grouplens.lenskit.util.table.writer.MultiplexedTableWriter;
import org.grouplens.lenskit.util.table.writer.TableWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Sets up and runs train-test evaluations.  This class can be used directly, but it will usually be controlled from
 * the `train-test` command line tool in turn driven by a Gradle script.  For a simpler way to programatically run an
 * evaluation, see {@link org.grouplens.lenskit.eval.traintest.SimpleEvaluator}, which provides a simplified interface
 * to train-test evaluations with cross-validation.
 *
 * A train-test experiment experiment consists of three things:
 *
 * - A collection of algorithms.
 * - A collection of train-test data sets.
 * - A collection of tasks, each of which performs an action on the recommender (e.g. predict users' test
 * ratings, or produce recommendations) and measures the recommender's performance on that task using one
 * or more metrics.
 *
 * Global output is aggregated into a CSV file; individual tasks or metrics may produce additional files.
 */
public class TrainTestExperiment {
    private static final Logger logger = LoggerFactory.getLogger(TrainTestExperiment.class);
    private Path outputFile;
    private Path userOutputFile;
    private Path cacheDir;
    private boolean sharesModelComponents = true;
    private int threadCount;
    private ClassLoader classLoader = ClassLoaders.inferDefault(TrainTestExperiment.class);

    private List<AlgorithmInstance> algorithms = new ArrayList<>();
    private List<DataSet> dataSets = new ArrayList<>();
    private List<EvalTask> tasks = new ArrayList<>();

    private TableWriter globalOutput;
    private TableWriter userOutput;
    private TableBuilder resultBuilder;
    private Closer resultCloser;

    /**
     * Set the primary output file.
     * @param out The file where the primary aggregate output should go.
     */
    public void setOutputFile(Path out) {
        outputFile = out;
    }

    /**
     * Get the primary output file.
     * @return The primary output file.
     */
    public Path getOutputFile() {
        return outputFile;
    }

    /**
     * Get the per-user output file.
     * @return The output file for per-user measurements.
     */
    public Path getUserOutputFile() {
        return userOutputFile;
    }

    /**
     * Set the per-user output file.
     * @param file The file for per-user measurements.
     */
    public void setUserOutputFile(Path file) {
        userOutputFile = file;
    }

    /**
     * Get the algorithm instances.
     * @return The algorithms to run.
     */
    public List<AlgorithmInstance> getAlgorithms() {
        return algorithms;
    }

    /**
     * Add an algorithm to the experiment.
     * @param algo The algorithm to add.
     */
    public void addAlgorithm(AlgorithmInstance algo) {
        algorithms.add(algo);
    }

    /**
     * Add multiple algorithm instances.
     * @param algos The algorithm instances to add.
     */
    public void addAlgorithms(List<AlgorithmInstance> algos) {
        algorithms.addAll(algos);
    }

    /**
     * Add an algorithm configured by a Groovy closure.  Mostly useful for testing.
     * @param name The algorithm name.
     * @param block The algorithm configuration block.
     */
    public void addAlgorithm(String name, Closure<?> block) {
        AlgorithmInstanceBuilder aib = new AlgorithmInstanceBuilder(name);
        LenskitConfiguration config = aib.getConfig();
        ConfigHelpers.configure(config, block);
        addAlgorithm(aib.build());
    }

    /**
     * Get the list of data sets to use.
     * @return The list of data sets to use.
     */
    public List<DataSet> getDataSets() {
        return dataSets;
    }

    /**
     * Add a data set.
     * @param ds The data set to add.
     */
    public void addDataSet(DataSet ds) {
        dataSets.add(ds);
    }

    /**
     * Add several data sets.
     * @param dss The data sets to add.
     */
    public void addDataSets(List<DataSet> dss) {
        dataSets.addAll(dss);
    }

    /**
     * Query whether this experiment will cache and share components.
     *
     * @return {@code true} if model components will be shared.
     * @see #setSharesModelComponents(boolean)
     */
    public boolean getSharesModelComponents() {
        return sharesModelComponents;
    }

    /**
     * Control whether model components will be shared.  If {@link #setCacheDirectory(Path)} is also set,
     * components will be cached on disk; otherwise, they will be opportunistically shared in memory.
     *
     * Cached output improves throughput and memory use, but makes build times effectively meaningless.  It
     * is turned on by default, but turn it off if you want to measure recommender build times.
     *
     * @param shares `true` to enable caching of shared model components.
     */
    public void setSharesModelComponents(boolean shares) {
        sharesModelComponents = shares;
    }

    /**
     * Get the cache directory for model components.
     * @return The directory where model components will be cached.
     */
    public Path getCacheDirectory() {
        return cacheDir;
    }

    /**
     * Set the cache directory for model components.
     * @param dir The directory where model components will be cached.
     */
    public void setCacheDirectory(Path dir) {
        cacheDir = dir;
    }

    /**
     * Get the number of threads that the experiment may use.
     *
     * @return The number of threads that the experiment may use.
     */
    public int getThreadCount() {
        int tc = threadCount;
        if (tc <= 0) {
            String prop = System.getProperty("lenskit.eval.threadCount");
            if (prop != null) {
                tc = Integer.parseInt(prop);
            }
        }
        if (tc <= 0) {
            tc = Runtime.getRuntime().availableProcessors();
        }
        return tc;
    }

    /**
     * Set the number of threads the experiment may use.
     *
     * @param tc The number of threads that the experiment may use.  If 0 (the default), consults the property
     *           `lenskit.eval.threadCount`, and if that is unset, uses as many threads as there
     *           are available processors according to {@link Runtime#availableProcessors()}.
     */
    public void setThreadCount(int tc) {
        threadCount = tc;
    }

    /**
     * Get the class loader for this experiment.
     * @return The class loader that will be used.
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Set the class loader for this experiment.
     * @param loader The class loader to use.
     */
    public void setClassLoader(ClassLoader loader) {
        classLoader = loader;
    }

    /**
     * Get the eval tasks to be used in this experiment.
     * @return The evaluation tasks to run.
     */
    public List<EvalTask> getTasks() {
        return tasks;
    }

    /**
     * Add an evaluation task.
     * @param task An evaluation task to run.
     */
    public void addTask(EvalTask task) {
        tasks.add(task);
    }

    /**
     * Get the global output table.
     * @return The global output table.
     */
    @Nonnull
    TableWriter getGlobalOutput() {
        Preconditions.checkState(resultBuilder != null, "Experiment has not been started");
        assert globalOutput != null;
        return globalOutput;
    }

    /**
     * Get the per-user output table.
     * @return The per-user output table.
     */
    @Nullable
    TableWriter getUserOutput() {
        Preconditions.checkState(resultBuilder != null, "Experiment has not been started");
        return userOutput;
    }

    /**
     * Run the experiment.
     * @return The global aggregate results from the experiment.
     */
    public Table run() {
        try {
            try {
                resultCloser = Closer.create();
                ExperimentOutputLayout layout = makeExperimentOutputLayout();
                openOutputs(layout);

                ListMultimap<UUID,Runnable> jobs = makeJobList();
                runJobList(jobs);

                // done before closing, but that is ok
                return resultBuilder.build();
            } catch (Throwable th) { //NOSONAR using closer
                throw resultCloser.rethrow(th);
            } finally {
                resultBuilder = null;
                resultCloser.close();
            }
        } catch (IOException ex) {
            throw new EvaluationException("I/O error in evaluation", ex);
        }
    }

    private ExperimentOutputLayout makeExperimentOutputLayout() {
        Set<String> dataColumns = Sets.newLinkedHashSet();
        Set<String> algoColumns = Sets.newLinkedHashSet();
        for (DataSet ds: getDataSets()) {
            dataColumns.addAll(ds.getAttributes().keySet());
        }
        for (AlgorithmInstance ai: getAlgorithms()) {
            algoColumns.addAll(ai.getAttributes().keySet());
        }
        return new ExperimentOutputLayout(dataColumns, algoColumns);
    }

    private void openOutputs(ExperimentOutputLayout eol) throws IOException {
        TableLayout globalLayout = makeGlobalResultLayout(eol);
        resultBuilder = resultCloser.register(new TableBuilder(globalLayout));
        if (outputFile != null) {
            TableWriter csvw = resultCloser.register(CSVWriter.open(outputFile.toFile(), globalLayout, CompressionMode.AUTO));
            globalOutput = resultCloser.register(new MultiplexedTableWriter(globalLayout, resultBuilder, csvw));
        } else {
            globalOutput = resultBuilder;
        }

        // FIXME Set up per-user output
    }

    private TableLayout makeGlobalResultLayout(ExperimentOutputLayout eol) {
        TableLayoutBuilder tlb = TableLayoutBuilder.copy(eol.getConditionLayout());
        tlb.addColumn("BuildTime")
           .addColumn("TestTime");
        // FIXME Support global metrics
        return tlb.build();
    }

    /**
     * Create the list of jobs to run in this experiment.
     * @return The jobs, as a multimap from isolation group IDs to tasks.
     */
    private ListMultimap<UUID,Runnable> makeJobList() {
        ComponentCache cache = null;
        if (sharesModelComponents) {
            cache = new ComponentCache(cacheDir, classLoader);
        }
        ListMultimap<UUID, Runnable> jobs = MultimapBuilder.linkedHashKeys()
                                                           .linkedListValues()
                                                           .build();
        for (DataSet ds: getDataSets()) {
            // TODO support global isolation
            UUID group = ds.getIsolationGroup();
            MergePool<Component,Dependency> pool = null;
            if (cache != null) {
                pool = MergePool.create();
            }
            for (AlgorithmInstance ai: getAlgorithms()) {
                ExperimentJob job = new ExperimentJob(this, ai, ds, cache, pool);
                jobs.put(group, job);
            }
        }

        return jobs;
    }

    /**
     * Run the jobs.
     * @param jobs The jobs to run.
     */
    private void runJobList(ListMultimap<UUID, Runnable> jobs) {
        ExecutorService service = null;
        ExecutorCompletionService<Void> ecs = null;
        int nthreads = getThreadCount();
        if (nthreads > 1) {
            logger.info("running with {} threads", nthreads);
            service = Executors.newFixedThreadPool(nthreads);
            ecs = new ExecutorCompletionService<>(service);
        } else {
            logger.info("running in a single thread");
        }
        try {
            for (UUID group: jobs.keySet()) {
                logger.info("running group {}", group);
                List<Future<?>> results = new ArrayList<>();
                for (Runnable job: jobs.get(group)) {
                    if (ecs == null) {
                        job.run();
                    } else {
                        results.add(ecs.submit(job, null));
                    }
                }
                if (ecs != null) {
                    // handle completion in order, cancelling tasks if we are interrupted or if there is an errors
                    int remaining = results.size();
                    while (remaining > 0) {
                        try {
                            Future<Void> r = ecs.take();
                            r.get();
                            remaining -= 1;
                        } catch (InterruptedException ex) {
                            logger.debug("thread interrupted, cancelling", ex);
                            for (Future<?> r: results) {
                                r.cancel(true);
                            }
                            // FIXME What should we do with remaining here?
                        } catch (ExecutionException ex) {
                            Throwables.propagateIfInstanceOf(ex.getCause(), EvaluationException.class);
                            for (Future<?> r: results) {
                                r.cancel(true);
                            }
                            throw new EvaluationException("error running evaluation", ex.getCause());
                        }
                    }
                }
            }
        } finally{
            if (service != null) {
                service.shutdown();
            }
        }
    }
}
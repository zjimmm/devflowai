package ai.devflow.worker;

/** Provider-neutral seam over "produce or review code" — today Spring AI, later possibly an external CLI agent. */
public interface CodingWorker {
    WorkerResult run(WorkerRequest request);
}

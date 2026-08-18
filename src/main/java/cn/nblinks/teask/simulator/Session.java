package cn.nblinks.teask.simulator;

import java.util.concurrent.Future;

import cn.nblinks.teask.constants.Status;

/**
 * A charging session for one slot of a charger.
 */
public class Session {
    private final String flowNo;
    private final int budget;
    private final long startTime;
    
    private Future<?> worker;
    private Status status;

    /**
     * The constructor for a charging session. The {@code startTime} is initialised
     * to the current time of instantiation and the {@code status} is initialised
     * as {@code Status.CHARGING}.
     * 
     * @param flowNo the flow number.
     * @param budget the budget for the charging session.
     */
    public Session(String flowNo, int budget) {
        this.flowNo = flowNo;
        this.budget = budget;
        startTime = System.currentTimeMillis();

        status = Status.CHARGING;
    }

    /**
     * Stops the execution of the worker of this charging session.
     * If the worker is still running, the execution will be
     * interrupted.
     */
    public void stopSession() {
        worker.cancel(true);
    }

    // -- getters ----------------------------------

    public String getFlowNo() {
        return flowNo;
    }

    public int getBudget() {
        return budget;
    }

    public long getStartTime() {
        return startTime;
    }

    public Status getStatus() {
        return status;
    }

    // -- setters ----------------------------------

    public void setWorker(Future<?> worker) {
        this.worker = worker;
    }
    
    public void setStatus(Status status) {
        this.status = status;
    }
}
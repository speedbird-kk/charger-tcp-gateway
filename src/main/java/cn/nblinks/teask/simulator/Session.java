package cn.nblinks.teask.simulator;

import cn.nblinks.teask.constants.Status;

/**
 * A charging session for one slot of a charger.
 */
public class Session {
    private final String flowNo;
    private final int budget;
    private final long startTime;

    private Status status;

    /**
     * The constructor for a charging session. The {@code startTime} is initialised
     * to the current time of instantiation and the {@code status} is initialised
     * as {@code Status.CHARGING}.
     * 
     * @param flowNo the flow number
     * @param budget the budget for the charge
     */
    public Session(String flowNo, int budget) {
        this.flowNo = flowNo;
        this.budget = budget;
        startTime = System.currentTimeMillis();

        status = Status.CHARGING;
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

    public void setStatus(Status status) {
        this.status = status;
    }
}
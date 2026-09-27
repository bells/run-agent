package cn.watsonzhu.runagent.model.running;

public enum DistanceType {
    FIVE_K(5_000), TEN_K(10_000), HALF_MARATHON(21_097.5), MARATHON(42_195);

    private final double meters;

    DistanceType(double meters) {
        this.meters = meters;
    }

    public double meters() {
        return meters;
    }
}

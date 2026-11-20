package com.serviceflow.ai.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 调度配置：权重、负载阈值、绩效样本门槛均可通过 application.yml 的 dispatch.* 覆盖，
 * 不在代码里写死。权重之和不等于 1 时由 DispatchScoringService 归一化。
 */
@Component
@ConfigurationProperties(prefix = "dispatch")
public class DispatchProperties {

    private Weights weights = new Weights();
    private Load load = new Load();
    private Performance performance = new Performance();
    private int topLimit = 5;

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights weights) {
        this.weights = weights;
    }

    public Load getLoad() {
        return load;
    }

    public void setLoad(Load load) {
        this.load = load;
    }

    public Performance getPerformance() {
        return performance;
    }

    public void setPerformance(Performance performance) {
        this.performance = performance;
    }

    public int getTopLimit() {
        return topLimit;
    }

    public void setTopLimit(int topLimit) {
        this.topLimit = topLimit;
    }

    public static class Weights {
        private double skill = 0.40;
        private double workload = 0.30;
        private double sla = 0.20;
        private double performance = 0.10;

        public double getSkill() {
            return skill;
        }

        public void setSkill(double skill) {
            this.skill = skill;
        }

        public double getWorkload() {
            return workload;
        }

        public void setWorkload(double workload) {
            this.workload = workload;
        }

        public double getSla() {
            return sla;
        }

        public void setSla(double sla) {
            this.sla = sla;
        }

        public double getPerformance() {
            return performance;
        }

        public void setPerformance(double performance) {
            this.performance = performance;
        }

        public double sum() {
            return skill + workload + sla + performance;
        }
    }

    public static class Load {
        private int lowMax = 49;
        private int normalMax = 79;
        private int highMax = 99;

        public int getLowMax() {
            return lowMax;
        }

        public void setLowMax(int lowMax) {
            this.lowMax = lowMax;
        }

        public int getNormalMax() {
            return normalMax;
        }

        public void setNormalMax(int normalMax) {
            this.normalMax = normalMax;
        }

        public int getHighMax() {
            return highMax;
        }

        public void setHighMax(int highMax) {
            this.highMax = highMax;
        }
    }

    public static class Performance {
        /** 低于该完成样本数时使用中性分 */
        private int sampleThreshold = 5;
        private int neutralScore = 60;
        /** 完成量达到该值即得满分 */
        private int fullVolume = 10;

        public int getSampleThreshold() {
            return sampleThreshold;
        }

        public void setSampleThreshold(int sampleThreshold) {
            this.sampleThreshold = sampleThreshold;
        }

        public int getNeutralScore() {
            return neutralScore;
        }

        public void setNeutralScore(int neutralScore) {
            this.neutralScore = neutralScore;
        }

        public int getFullVolume() {
            return fullVolume;
        }

        public void setFullVolume(int fullVolume) {
            this.fullVolume = fullVolume;
        }
    }
}

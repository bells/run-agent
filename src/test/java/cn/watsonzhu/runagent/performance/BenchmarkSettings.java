package cn.watsonzhu.runagent.performance;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import cn.watsonzhu.runagent.config.PerformanceProperties;

public record BenchmarkSettings(boolean live, boolean enabled, int runs, int warmup, Duration timeout,
        String baseUrl, String apiKey, String defaultModel, String fastModel, String strongModel,
        Map<String, String> priceSettings, Map<ModelStage, ModelReasoningEffort> stageReasoningEffort) {
    public static BenchmarkSettings offline() {
        return new BenchmarkSettings(false,true,1,0,Duration.ofSeconds(10),"","","synthetic-default","synthetic-fast","synthetic-strong",Map.of(),Map.of());
    }
    public static BenchmarkSettings from(Map<String,String> env, boolean live) {
        if (!live) return offline();
        int runs, seconds;
        try { runs=Integer.parseInt(env.getOrDefault("PERFORMANCE_BENCHMARK_RUNS","3"));
            seconds=Integer.parseInt(env.getOrDefault("PERFORMANCE_BENCHMARK_TIMEOUT_SECONDS","180")); }
        catch(RuntimeException exception){throw new IllegalArgumentException("Invalid benchmark settings");}
        if(runs<1 || runs>10 || seconds<1 || seconds>3600) throw new IllegalArgumentException("Invalid benchmark limits");
        String endpoint=env.getOrDefault("AI_GATEWAY_BASE_URL","");
        if(!endpoint.isBlank()) {
            URI uri;
            try{uri=URI.create(endpoint);}catch(RuntimeException exception){throw new IllegalArgumentException("Invalid benchmark endpoint");}
            if(!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("Invalid benchmark endpoint");
        }
        var prices=new java.util.HashMap<String,String>();
        for(var tier:ModelTier.values())for(String side:java.util.List.of("INPUT","OUTPUT")) {
            String key=tier.name()+"_MODEL_"+side+"_PRICE_PER_MILLION";
            prices.put(key,env.getOrDefault(key,"-1"));
        }
        prices.put("PERFORMANCE_CURRENCY",env.getOrDefault("PERFORMANCE_CURRENCY","USD"));
        var reasoning=new EnumMap<ModelStage,ModelReasoningEffort>(ModelStage.class);
        for(var stage:ModelStage.values()) {
            String value=env.get("PERFORMANCE_REASONING_EFFORT_"+stage.name());
            if(value==null)continue;
            try { reasoning.put(stage,ModelReasoningEffort.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT))); }
            catch(IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid benchmark reasoning policy"); }
        }
        return new BenchmarkSettings(true,Boolean.parseBoolean(env.getOrDefault("LIVE_PERFORMANCE_BENCHMARK_ENABLED","false")),runs,1,
                Duration.ofSeconds(seconds),endpoint,env.getOrDefault("AI_GATEWAY_API_KEY",""),env.getOrDefault("AI_GATEWAY_MODEL",""),
                env.getOrDefault("AI_FAST_MODEL",""),env.getOrDefault("AI_STRONG_MODEL",""),Map.copyOf(prices),Map.copyOf(reasoning));
    }
    public boolean ready(){return enabled && (!live || !baseUrl.isBlank() && !apiKey.isBlank() && !defaultModel.isBlank());}
    public PerformanceProperties properties(PerformanceMode mode) {
        var models=new EnumMap<ModelTier,PerformanceProperties.ModelConfig>(ModelTier.class);
        models.put(ModelTier.DEFAULT,new PerformanceProperties.ModelConfig(defaultModel));
        models.put(ModelTier.FAST,new PerformanceProperties.ModelConfig(fastModel));
        models.put(ModelTier.STRONG,new PerformanceProperties.ModelConfig(strongModel));
        var prices=new EnumMap<ModelTier,PerformanceProperties.Price>(ModelTier.class);
        for(var tier:ModelTier.values()) {
            String prefix=tier.name()+"_MODEL_";
            // Synthetic prices exercise arithmetic only; they are not vendor quotes.
            prices.put(tier,new PerformanceProperties.Price(new BigDecimal(priceSettings.getOrDefault(prefix+"INPUT_PRICE_PER_MILLION",live?"-1":"2")),
                    new BigDecimal(priceSettings.getOrDefault(prefix+"OUTPUT_PRICE_PER_MILLION",live?"-1":"4"))));
        }
        return new PerformanceProperties(mode,16,1500,30000,models,Map.of(),new PerformanceProperties.Context(600,9000),
                new PerformanceProperties.Pricing(priceSettings.getOrDefault("PERFORMANCE_CURRENCY","USD"),prices),stageReasoningEffort);
    }
    @Override public String toString(){return "BenchmarkSettings[live="+live+", enabled="+enabled+", runs="+runs+"]";}
}

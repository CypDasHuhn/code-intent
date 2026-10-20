package dev.cint.core.rulepack;

import dev.cint.api.ModelKt;
import dev.cint.api.Rule;
import dev.cint.api.RulePack;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FixtureRawRuleListPack implements RulePack {
    @Override
    public String getId() {
        return "fixture-raw-rule-list";
    }

    @Override
    public Set<String> getLanguages() {
        return Set.of("kotlin");
    }

    @Override
    public int getApiVersion() {
        return ModelKt.CINT_API_VERSION;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public List<Rule> rules() {
        List raw = new ArrayList();
        raw.add("not-a-rule");
        return raw;
    }
}

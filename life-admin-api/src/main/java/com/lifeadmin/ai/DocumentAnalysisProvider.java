package com.lifeadmin.ai;
import com.lifeadmin.dto.AnalysisContent;
/** Accepts extracted text only; adapters never access uploaded files or workspaces. */
public interface DocumentAnalysisProvider {
    AnalysisContent analyze(String documentText);
}

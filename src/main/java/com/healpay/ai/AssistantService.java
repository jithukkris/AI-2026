package com.healpay.ai;

/** Natural-language Q&A over reconciliation data ("Why is TXN-100045 pending?"). */
public interface AssistantService {

    String ask(String question);

    String sourceName();
}

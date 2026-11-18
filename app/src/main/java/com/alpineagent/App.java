package com.alpineagent;

import android.app.Application;

/** Singleton de processo: Activity, tarefas agendadas e serviços compartilham as mesmas instâncias. */
public class App extends Application {
    Alpine alpine;
    Providers providers;
    Skills skills;
    Mcp mcp;
    Scheduler scheduler;
    Daytona daytona;
    Tools tools;

    @Override public void onCreate() {
        super.onCreate();
        alpine = new Alpine(this);
        providers = new Providers(this);
        skills = new Skills(this);
        mcp = new Mcp(this, alpine);
        scheduler = new Scheduler(this);
        daytona = new Daytona(this);
        tools = new Tools(this, alpine, skills, mcp, scheduler, daytona);
    }

    Agent newAgent(Agent.Log l) { return new Agent(tools, skills, l); }
}

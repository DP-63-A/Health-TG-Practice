package org.healthtg.seed;

import org.healthtg.HealthTgApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.LocalDate;
import java.util.Map;

public final class DemoDataCommand {
    private DemoDataCommand() {
    }

    public static void main(String[] args) {
        Command command = Command.parse(args);
        Map<String, String> environment = System.getenv();
        String mongoUri = environment.get("MONGODB_URI");
        DemoEnvironmentGuard.requireDemoEnvironment(environment.get("HEALTH_TG_DEMO"), mongoUri);

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(HealthTgApplication.class)
                .web(WebApplicationType.NONE)
                .run()) {
            DemoDatasetService service = context.getBean(DemoDatasetService.class);
            if (command.action().equals("seed")) {
                int entries = service.seed(environment.get("HEALTH_TG_DEMO"), mongoUri,
                        DemoProfileOwners.fromEnvironment(environment), command.seed(), command.startDate());
                System.out.println("Seeded or verified " + entries + " logical demo entries across 3 profiles.");
            } else {
                long entries = service.reset(environment.get("HEALTH_TG_DEMO"), mongoUri);
                System.out.println("Removed " + entries + " tagged demo entries.");
            }
        }
    }

    record Command(String action, LocalDate startDate, long seed) {
        static Command parse(String[] args) {
            if (args.length == 0 || (!args[0].equals("seed") && !args[0].equals("reset"))) {
                throw new IllegalArgumentException("Usage: seed [--start-date=YYYY-MM-DD] [--seed=NUMBER] | reset");
            }
            String action = args[0];
            LocalDate startDate = SyntheticDatasetGenerator.DEFAULT_START_DATE;
            long seed = SyntheticDatasetGenerator.DEFAULT_SEED;
            if (action.equals("reset") && args.length != 1) {
                throw new IllegalArgumentException("Reset accepts no parameters");
            }
            for (int index = 1; index < args.length; index++) {
                String argument = args[index];
                if (argument.startsWith("--start-date=") && action.equals("seed")) {
                    startDate = LocalDate.parse(argument.substring("--start-date=".length()));
                } else if (argument.startsWith("--seed=") && action.equals("seed")) {
                    seed = Long.parseLong(argument.substring("--seed=".length()));
                } else {
                    throw new IllegalArgumentException("Unsupported demo data parameter");
                }
            }
            return new Command(action, startDate, seed);
        }
    }
}

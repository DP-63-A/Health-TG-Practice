package org.healthtg.seed;

import org.healthtg.HealthTgApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

/**
 * Local command line entry point: {@code seed} or {@code reset} followed by ordinary Spring arguments such as
 * {@code --health-tg.seed.random-seed=1}. It is deliberately not exposed through HTTP.
 */
public final class SeedCli {
    private SeedCli() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || !(args[0].equals("seed") || args[0].equals("reset"))) {
            System.err.println("Usage: SeedCli <seed|reset> [--health-tg.seed.random-seed=N] "
                    + "[--health-tg.seed.start-date=YYYY-MM-DD]");
            System.exit(64);
        }
        System.exit(run(args[0], Arrays.copyOfRange(args, 1, args.length)));
    }

    private static int run(String command, String[] springArgs) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(HealthTgApplication.class)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run(springArgs)) {
            SeedService service = context.getBean(SeedService.class);
            if (command.equals("seed")) {
                print(service.seed());
            } else {
                System.out.println("Reset removed " + service.reset() + " seed entries.");
            }
            return 0;
        } catch (SeedRefusedException refused) {
            System.err.println(refused.getMessage());
            return 2;
        } catch (RuntimeException failure) {
            System.err.println("Seed command failed: " + failure.getClass().getSimpleName() + ": "
                    + failure.getMessage());
            return 1;
        }
    }

    private static void print(SeedReport report) {
        System.out.println("Synthetic educational data (fictional, not real health data). random-seed="
                + report.randomSeed() + " start-date=" + report.startDate());
        for (SeedReport.ProfileReport profile : report.profiles()) {
            System.out.println(profile.profile().code() + ": user=" + profile.userId() + " days=" + profile.days()
                    + " confirmed=" + profile.confirmedByType() + " changed=" + profile.changedRecords()
                    + " redelivered=" + profile.redeliveredRecords() + " cancelled-drafts="
                    + profile.cancelledDrafts() + " empty-days=" + profile.emptyDays());
        }
    }
}

package se.swedsoft.bookkeeping.data.system;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSVoucherTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for voucher-template persistence.
 */
@Tag("integration")
class SSVoucherTemplateIntegrationTest {

    private static final String TEMPLATE_NAME = "Integration template replacement";

    @BeforeAll
    static void openDatabase() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @BeforeEach
    void clearExistingTemplate() {
        deleteTemplate();
        SSDBTestFixture.resetCaches();
    }

    @AfterEach
    void cleanUp() {
        deleteTemplate();
        SSDBTestFixture.drainUncaughtExceptions();
    }

    @Test
    void savingTemplateWithExistingNameReplacesIt() {
        SSVoucherTemplate original = template(TEMPLATE_NAME);
        SSDB.getInstance().addVoucherTemplate(original);

        SSVoucherTemplate replacement = template(TEMPLATE_NAME);
        SSDB.getInstance().addVoucherTemplate(replacement);

        List<SSVoucherTemplate> matchingTemplates = SSDB.getInstance().getVoucherTemplates().stream()
                .filter(template -> TEMPLATE_NAME.equals(template.getDescription()))
                .toList();
        assertThat(matchingTemplates).hasSize(1);
    }

    private static SSVoucherTemplate template(String name) {
        SSVoucherTemplate template = new SSVoucherTemplate();
        template.setDescription(name);
        return template;
    }

    private static void deleteTemplate() {
        SSDB.getInstance().deleteVoucherTemplate(template(TEMPLATE_NAME));
    }
}

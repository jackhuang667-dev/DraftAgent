package com.draftagent.business;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.draftagent.DraftAgentApplication;
import com.draftagent.business.dto.DraftCreateResponse;
import com.draftagent.business.entity.AgentToolExecution;
import com.draftagent.business.entity.Conversation;
import com.draftagent.business.entity.Draft;
import com.draftagent.business.mapper.AgentToolExecutionMapper;
import com.draftagent.business.mapper.ConversationMapper;
import com.draftagent.business.mapper.DraftMapper;
import com.draftagent.business.service.ToolExecutionService;
import com.draftagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ToolExecutionService 的真实 MySQL 集成测试（幂等 + 事务原子性 + 并发）。
 *
 * <p>说明：本测试依赖 {@code agent_tool_execution} 表。若该表尚未创建（本批不执行 DDL），
 * 测试会以 Assumptions 跳过并给出明确提示，绝不把跳过当作通过。</p>
 *
 * <p>使用唯一随机 requestId 隔离数据；仅清理本测试创建的会话/执行记录/草案，不触碰其他数据。
 * 不调用 DeepSeek、不经过 AiService、不创建非测试草案。</p>
 */
@SpringBootTest(classes = DraftAgentApplication.class)
class ToolExecutionServiceMySqlIntegrationTest {

    private static final String TOOL = "createDraft";

    private static final Long USER_ID = 1001L;

    private static final Long TENANT_ID = 10L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ToolExecutionService toolExecutionService;

    @Autowired
    private AgentToolExecutionMapper agentToolExecutionMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private DraftMapper draftMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Set<Long> createdDraftIds = ConcurrentHashMap.newKeySet();

    private final Set<Long> conversationPks = ConcurrentHashMap.newKeySet();

    private String requestId;

    private Long conversationPk;

    private String turnId;

    @BeforeEach
    void setUp() {
        try (Connection connection = dataSource.getConnection()) {
            boolean ready = tableExists(connection, "conversation")
                    && tableExists(connection, "draft")
                    && tableExists(connection, "agent_tool_execution");
            Assumptions.assumeTrue(ready,
                    "agent_tool_execution 表不存在（或依赖表缺失）：请先手动执行 docs/sql/agent_tool_execution.sql 后重跑本测试");
        }
        catch (SQLException e) {
            Assumptions.assumeTrue(false, "MySQL 不可用，跳过真实集成测试: " + e.getClass().getSimpleName());
        }

        this.requestId = UUID.randomUUID().toString();
        this.turnId = UUID.randomUUID().toString();

        Conversation conversation = new Conversation();
        conversation.setConversationId(UUID.randomUUID().toString());
        conversation.setStatus("ACTIVE");
        conversation.setLastMessageSeq(0L);
        this.conversationMapper.insert(conversation);
        this.conversationPk = conversation.getId();
        this.conversationPks.add(this.conversationPk);
    }

    @AfterEach
    void cleanupSafely() {
        for (Long pk : this.conversationPks) {
            try {
                if (pk != null) {
                    this.agentToolExecutionMapper.delete(new LambdaQueryWrapper<AgentToolExecution>()
                            .eq(AgentToolExecution::getConversationPk, pk));
                }
            }
            catch (Exception ignored) {
                // 定向清理失败不掩盖原始结果
            }
        }
        try {
            if (this.requestId != null) {
                this.draftMapper.delete(new LambdaQueryWrapper<Draft>()
                        .likeRight(Draft::getTitle, "TE-" + this.requestId));
            }
        }
        catch (Exception ignored) {
            // ignore
        }
        for (Long draftId : this.createdDraftIds) {
            try {
                if (draftId != null) {
                    this.draftMapper.deleteById(draftId);
                }
            }
            catch (Exception ignored) {
                // ignore
            }
        }
        for (Long pk : this.conversationPks) {
            try {
                if (pk != null) {
                    this.conversationMapper.deleteById(pk);
                }
            }
            catch (Exception ignored) {
                // 先删执行记录再删会话（外键）
            }
        }
    }

    @Test
    void firstExecutionCreatesDraftAndSuccessRecord() {
        DraftCreateResponse response = this.toolExecutionService.executeCreateDraft(
                this.requestId, this.conversationPk, this.turnId, TOOL, title("A"), content("A"), USER_ID, TENANT_ID);
        this.createdDraftIds.add(response.getId());

        assertThat(response.getId()).isNotNull();
        Draft draft = this.draftMapper.selectById(response.getId());
        assertThat(draft).isNotNull();
        assertThat(draft.getTitle()).isEqualTo(title("A"));

        AgentToolExecution execution = findExecution(this.requestId);
        assertThat(execution).isNotNull();
        assertThat(execution.getStatus()).isEqualTo("SUCCESS");
        assertThat(execution.getResultRef()).isEqualTo(String.valueOf(response.getId()));
        assertThat(execution.getExecutionId()).isNotBlank();
        assertThat(execution.getConversationPk()).isEqualTo(this.conversationPk);
        assertThat(execution.getTurnId()).isEqualTo(this.turnId);
        assertThat(execution.getArguments()).contains("title");
    }

    @Test
    void sameRequestAndArgsReturnsSameDraft() {
        DraftCreateResponse first = this.toolExecutionService.executeCreateDraft(
                this.requestId, this.conversationPk, this.turnId, TOOL, title("A"), content("A"), USER_ID, TENANT_ID);
        this.createdDraftIds.add(first.getId());

        DraftCreateResponse second = this.toolExecutionService.executeCreateDraft(
                this.requestId, this.conversationPk, this.turnId, TOOL, title("A"), content("A"), USER_ID, TENANT_ID);
        this.createdDraftIds.add(second.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(countDraftsByTitle(title("A"))).isEqualTo(1L);
        assertThat(countExecutions(this.requestId)).isEqualTo(1L);
    }

    @Test
    void differentRequestIdCreatesDifferentDraft() {
        DraftCreateResponse first = this.toolExecutionService.executeCreateDraft(
                this.requestId, this.conversationPk, this.turnId, TOOL, title("A"), content("A"), USER_ID, TENANT_ID);
        this.createdDraftIds.add(first.getId());

        String otherRequestId = UUID.randomUUID().toString();
        String otherTitle = "TE-" + otherRequestId + "-A";
        DraftCreateResponse second = this.toolExecutionService.executeCreateDraft(
                otherRequestId, this.conversationPk, this.turnId, TOOL, otherTitle, "other-content", USER_ID, TENANT_ID);
        this.createdDraftIds.add(second.getId());

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(countDraftsByTitle(otherTitle)).isEqualTo(1L);
    }

    @Test
    void sameRequestDifferentArgsIsConflict() {
        DraftCreateResponse first = this.toolExecutionService.executeCreateDraft(
                this.requestId, this.conversationPk, this.turnId, TOOL, title("A"), content("A"), USER_ID, TENANT_ID);
        this.createdDraftIds.add(first.getId());

        assertThatThrownBy(() -> this.toolExecutionService.executeCreateDraft(
                this.requestId, this.conversationPk, this.turnId, TOOL, title("B"), content("B"), USER_ID, TENANT_ID))
                .isInstanceOf(ServiceException.class);

        assertThat(countDraftsByTitle(title("B"))).isZero();
        assertThat(countDraftsByTitle(title("A"))).isEqualTo(1L);
        assertThat(countExecutions(this.requestId)).isEqualTo(1L);
    }

    @Test
    void transactionFailureRollsBackDraftAndExecutionTogether() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(this.transactionManager);
        AtomicReference<Long> draftIdRef = new AtomicReference<>();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            DraftCreateResponse response = this.toolExecutionService.executeCreateDraft(
                    this.requestId, this.conversationPk, this.turnId, TOOL, title("A"), content("A"), USER_ID, TENANT_ID);
            draftIdRef.set(response.getId());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(draftIdRef.get()).isNotNull();
        assertThat(this.draftMapper.selectById(draftIdRef.get())).isNull();
        assertThat(findExecution(this.requestId)).isNull();
    }

    @Test
    void concurrentSameRequestCreatesOnlyOneDraft() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DraftCreateResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return this.toolExecutionService.executeCreateDraft(
                            this.requestId, this.conversationPk, this.turnId, TOOL, title("A"), content("A"), USER_ID, TENANT_ID);
                }));
            }
            start.countDown();

            List<Long> successIds = new ArrayList<>();
            int conflicts = 0;
            for (Future<DraftCreateResponse> future : futures) {
                try {
                    DraftCreateResponse response = future.get(15, TimeUnit.SECONDS);
                    successIds.add(response.getId());
                    this.createdDraftIds.add(response.getId());
                }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof ServiceException) {
                        conflicts++;
                    }
                    else {
                        throw new AssertionError("并发执行出现非预期异常", e.getCause());
                    }
                }
            }

            assertThat(successIds.size() + conflicts).isEqualTo(threads);
            assertThat(successIds).isNotEmpty();
            if (successIds.size() == threads) {
                assertThat(successIds.get(0)).isEqualTo(successIds.get(1));
            }
            assertThat(countDraftsByTitle(title("A"))).isEqualTo(1L);
            assertThat(countExecutions(this.requestId)).isEqualTo(1L);
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameRequestDifferentConversationIsConflict() {
        DraftCreateResponse first = this.toolExecutionService.executeCreateDraft(
                this.requestId, this.conversationPk, this.turnId, TOOL, title("A"), content("A"), USER_ID, TENANT_ID);
        this.createdDraftIds.add(first.getId());

        Long otherConversationPk = newConversation();

        assertThatThrownBy(() -> this.toolExecutionService.executeCreateDraft(
                this.requestId, otherConversationPk, this.turnId, TOOL, title("A"), content("A"), USER_ID, TENANT_ID))
                .isInstanceOf(ServiceException.class);

        assertThat(countDraftsByTitle(title("A"))).isEqualTo(1L);
        assertThat(countExecutions(this.requestId)).isEqualTo(1L);
    }

    private Long newConversation() {
        Conversation conversation = new Conversation();
        conversation.setConversationId(UUID.randomUUID().toString());
        conversation.setUserId(USER_ID);
        conversation.setTenantId(TENANT_ID);
        conversation.setStatus("ACTIVE");
        conversation.setLastMessageSeq(0L);
        this.conversationMapper.insert(conversation);
        this.conversationPks.add(conversation.getId());
        return conversation.getId();
    }

    private AgentToolExecution findExecution(String idempotencyKey) {
        return this.agentToolExecutionMapper.selectOne(new LambdaQueryWrapper<AgentToolExecution>()
                .eq(AgentToolExecution::getToolName, TOOL)
                .eq(AgentToolExecution::getIdempotencyKey, idempotencyKey));
    }

    private long countExecutions(String idempotencyKey) {
        return this.agentToolExecutionMapper.selectCount(new LambdaQueryWrapper<AgentToolExecution>()
                .eq(AgentToolExecution::getConversationPk, this.conversationPk)
                .eq(AgentToolExecution::getIdempotencyKey, idempotencyKey));
    }

    private long countDraftsByTitle(String title) {
        return this.draftMapper.selectCount(new LambdaQueryWrapper<Draft>().eq(Draft::getTitle, title));
    }

    private String title(String suffix) {
        return "TE-" + this.requestId + "-" + suffix;
    }

    private String content(String suffix) {
        return "content-" + this.requestId + "-" + suffix;
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SHOW TABLES LIKE ?")) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}

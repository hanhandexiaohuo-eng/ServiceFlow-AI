package com.serviceflow.ai.ticket.service;

import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.ticket.dto.TicketCreateRequest;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import com.serviceflow.ai.ticket.dto.TicketUpdateRequest;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.enums.Priority;
import com.serviceflow.ai.ticket.enums.TicketStatus;
import com.serviceflow.ai.ticket.enums.TicketType;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 工单业务层：所有业务规则集中在这里（编号生成、枚举校验、404 语义），
 * Controller 只做参数接收和响应组装。
 *
 * @Transactional：方法内多次数据库操作要么全部成功，要么全部回滚。
 * 目前每个方法只有一次写操作，加上它是为后续"创建工单同时写操作日志"这类扩展做准备，
 * 也是企业开发的默认习惯。
 */
@Service
public class TicketService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NO_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TicketRepository ticketRepository;

    public TicketService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public TicketResponse create(TicketCreateRequest req) {
        Ticket t = new Ticket();
        t.setTicketNo(generateTicketNo());
        t.setTitle(req.title());
        t.setDescription(req.description());
        t.setType(parseEnum(TicketType.class, req.type(), "工单类型").name());
        // 创建时不填优先级默认 MEDIUM，状态固定 OPEN
        t.setPriority(req.priority() == null || req.priority().isBlank()
                ? Priority.MEDIUM.name()
                : parseEnum(Priority.class, req.priority(), "优先级").name());
        t.setStatus(TicketStatus.OPEN.name());
        // 创建人来自登录上下文（显示名 name），客户端无权指定，防止伪造
        t.setCreator(UserContext.getRequired().name());
        t.setAssignee(req.assignee());
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        return TicketResponse.from(ticketRepository.insert(t));
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> page(String status, String priority, int page, int size) {
        if (status != null && !status.isBlank()) {
            parseEnum(TicketStatus.class, status, "状态");
        }
        if (priority != null && !priority.isBlank()) {
            parseEnum(Priority.class, priority, "优先级");
        }
        List<TicketResponse> list = ticketRepository.findPage(status, priority, page, size)
                .stream().map(TicketResponse::from).toList();
        long total = ticketRepository.count(status, priority);
        return new PageResponse<>(list, total, page, size);
    }

    @Transactional(readOnly = true)
    public TicketResponse getById(Long id) {
        return ticketRepository.findById(id)
                .map(TicketResponse::from)
                .orElseThrow(() -> new NotFoundException("工单不存在：id=" + id));
    }

    @Transactional
    public TicketResponse update(Long id, TicketUpdateRequest req) {
        Ticket t = ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("工单不存在：id=" + id));
        t.setTitle(req.title());
        t.setDescription(req.description());
        t.setType(parseEnum(TicketType.class, req.type(), "工单类型").name());
        t.setPriority(parseEnum(Priority.class, req.priority(), "优先级").name());
        t.setStatus(parseEnum(TicketStatus.class, req.status(), "状态").name());
        t.setAssignee(req.assignee());
        ticketRepository.update(t);
        return getById(id);
    }

    @Transactional
    public void delete(Long id) {
        // 删除是高危操作，仅管理员。权限判断放在 Service 而非前端，前端隐藏按钮只是体验优化
        if (UserContext.getRequired().role() != UserRole.ADMIN) {
            throw new ForbiddenException("仅管理员可删除工单");
        }
        int affected = ticketRepository.deleteById(id);
        if (affected == 0) {
            throw new NotFoundException("工单不存在：id=" + id);
        }
    }

    /** 字符串转枚举，非法值抛 IllegalArgumentException（全局异常处理器转 400） */
    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value, String fieldName) {
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + "不合法：" + value);
        }
    }

    /** 工单编号：SF + 日期 + 4 位随机字符，撞号时重试（唯一索引兜底） */
    private String generateTicketNo() {
        for (int i = 0; i < 5; i++) {
            StringBuilder suffix = new StringBuilder(4);
            for (int j = 0; j < 4; j++) {
                suffix.append(NO_CHARS.charAt(RANDOM.nextInt(NO_CHARS.length())));
            }
            String no = "SF" + LocalDateTime.now().format(NO_DATE) + "-" + suffix;
            if (!ticketRepository.existsByTicketNo(no)) {
                return no;
            }
        }
        throw new IllegalStateException("工单编号生成失败，请重试");
    }
}

package com.lifeadmin.controller;
import com.lifeadmin.service.InboxService;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/inbox")
public class InboxController {
 private final InboxService inbox;
 public InboxController(InboxService inbox){this.inbox=inbox;}
 @GetMapping public InboxService.Page get(@AuthenticationPrincipal AccountPrincipal user,@RequestParam(defaultValue="all") String filter,@RequestParam(defaultValue="urgent") String sort,@RequestParam(defaultValue="0") int page){return inbox.get(user.id(),filter,sort,page);}
}

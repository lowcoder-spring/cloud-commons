package icu.lowcoder.spring.cloud.authentication.controller;

import icu.lowcoder.spring.commons.wechat.WeChatClient;
import icu.lowcoder.spring.commons.wechat.model.*;
import icu.lowcoder.spring.cloud.authentication.config.WeChatGrantProperties;
import icu.lowcoder.spring.cloud.authentication.dao.AccountRepository;
import icu.lowcoder.spring.cloud.authentication.dao.WeChatAppBindingRepository;
import icu.lowcoder.spring.cloud.authentication.dict.WeChatAppType;
import icu.lowcoder.spring.cloud.authentication.dto.AddWeAppBindingRequest;
import icu.lowcoder.spring.cloud.authentication.dto.AddWebPageWeChatBindingRequest;
import icu.lowcoder.spring.cloud.authentication.dto.WeChatBindPhoneRequest;
import icu.lowcoder.spring.cloud.authentication.dto.WeChatBindingResponse;
import icu.lowcoder.spring.cloud.authentication.entity.Account;
import icu.lowcoder.spring.cloud.authentication.entity.WeChatAppBinding;
import icu.lowcoder.spring.cloud.authentication.manager.AccountMergeManager;
import icu.lowcoder.spring.cloud.authentication.oauth2.provider.wechat.WeChatApp;
import icu.lowcoder.spring.cloud.authentication.service.AccountWeChatAppBindingsService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import javax.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
public class AccountWeChatAppBindingsController implements AccountWeChatAppBindingsService {
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private WeChatClient weChatClient;
    @Autowired
    private WeChatGrantProperties weChatGrantProperties;
    @Autowired
    private WeChatAppBindingRepository weChatAppBindingRepository;
    @Autowired
    private AccountMergeManager accountMergeManager;

    @Override
    @Transactional
    @PreAuthorize("(#oauth2.client and #oauth2.clientHasRole('ROLE_SERVICE_CLIENT')) or (#oauth2.user and #accountId.equals(authentication.principal.id)) ")
    public void addWebAppBinding(@PathVariable UUID accountId, @Valid @RequestBody AddWebPageWeChatBindingRequest request) {
        Account account = getAccount(accountId);

        // ??????APP??????
        WeChatApp weChatApp = getWeChatApp(request.getAppId());

        // code ??????????????????
        try {
            // token
            WebUserAccessToken webUserAccessToken = weChatClient.getWebUserAccessToken(request.getCode(), request.getAppId(), weChatApp.getSecret());
            // user info
            WebUserInfo webUserInfo;
            if(webUserAccessToken.getScope().contains("snsapi_userinfo")) {
                webUserInfo = weChatClient.getWebUserInfo(webUserAccessToken.getAccessToken(), webUserAccessToken.getOpenid());
            } else {
                webUserInfo = new WebUserInfo();
                webUserInfo.setOpenid(webUserAccessToken.getOpenid());
            }

            // ?????????????????????
            WeChatAppBinding bound = weChatAppBindingRepository.findOneByAccountAndAppId(account, request.getAppId());
            if (bound == null) {
                // ????????????
                bound = new WeChatAppBinding();
                bound.setAccount(account);
                bound.setUnionId(webUserInfo.getUnionid());
                bound.setOpenId(webUserInfo.getOpenid());
                bound.setAppType(WeChatAppType.WEB_APP);
                bound.setAppId(request.getAppId());

                account.getWeChatAppBindings().add(bound);
            } else {
                // ????????????
                if (StringUtils.isNotBlank(webUserInfo.getUnionid())) {
                    bound.setUnionId(webUserInfo.getUnionid());
                }
                if (StringUtils.isNotBlank(webUserInfo.getOpenid())) {
                    bound.setOpenId(webUserInfo.getOpenid());
                }
            }
        } catch (Exception e) {
            log.warn("????????????????????????????????????:" + e.getMessage(), e);
            throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "??????????????????????????????");
        }
    }

    @Override
    @Transactional
    @PreAuthorize("(#oauth2.client and #oauth2.clientHasRole('ROLE_SERVICE_CLIENT')) or (#oauth2.user and #accountId.equals(authentication.principal.id)) ")
    public void deleteAppBinding(@PathVariable UUID accountId,@PathVariable String appId) {
        Account account = getAccount(accountId);

        // ?????????????????????
        WeChatAppBinding bound = weChatAppBindingRepository.findOneByAccountAndAppId(account,appId);
        if(bound!=null){
            bound.setDeleted(true);
        }
    }

    @Override
    @Transactional
    @PreAuthorize("(#oauth2.client and #oauth2.clientHasRole('ROLE_SERVICE_CLIENT')) or (#oauth2.user and #accountId.equals(authentication.principal.id)) ")
    public void addMiniProgramBinding(@PathVariable UUID accountId, @Valid @RequestBody AddWeAppBindingRequest request) {
        Account account = getAccount(accountId);

        // ??????APP??????
        WeChatApp weChatApp = getWeChatApp(request.getAppId());

        // code2session ??????????????????
        try {
            // token
            SessionKey sessionKey = weChatClient.code2Session(request.getJsCode(), request.getAppId(), weChatApp.getSecret());

            // ?????????????????????
            WeChatAppBinding bound = weChatAppBindingRepository.findOneByAccountAndAppId(account, request.getAppId());
            if (bound == null) {
                // ????????????
                bound = new WeChatAppBinding();
                bound.setAccount(account);
                bound.setOpenId(sessionKey.getOpenid());
                bound.setAppType(WeChatAppType.MINI_PROGRAM);
                bound.setAppId(request.getAppId());

                account.getWeChatAppBindings().add(bound);
            } else {
                if (StringUtils.isNotBlank(sessionKey.getOpenid())) {
                    bound.setOpenId(sessionKey.getOpenid());
                }
            }

            if (StringUtils.isNotBlank(request.getEncryptedData())) {
                // ??????????????????
                UserInfo userInfo = weChatClient.decryptData(request.getEncryptedData(), sessionKey.getSessionKey(), request.getIv(), UserInfo.class);
                if (userInfo != null && StringUtils.isNotBlank(userInfo.getUnionId())) {
                    bound.setUnionId(userInfo.getUnionId());
                }
            }
        } catch (Exception e) {
            log.warn("????????????????????????????????????:" + e.getMessage(), e);
            throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "??????????????????????????????");
        }
    }

    @Override
    @PreAuthorize("(#oauth2.client and #oauth2.clientHasRole('ROLE_SERVICE_CLIENT')) or (#oauth2.user and #accountId.equals(authentication.principal.id)) ")
    public WeChatBindingResponse getByAppId(@PathVariable UUID accountId, @PathVariable String appId) {
        Account account = getAccount(accountId);

        WeChatAppBinding binding = weChatAppBindingRepository.findOneByAccountAndAppId(account, appId);
        if (binding != null) {
            WeChatBindingResponse resp = new WeChatBindingResponse();
            BeanUtils.copyProperties(binding, resp);
            return resp;
        }

        return null;
    }

    @Override
    @PreAuthorize("#oauth2.user and #accountId.equals(authentication.principal.id)")
    @Transactional
    public void bindPhone(@PathVariable UUID accountId, @Valid @RequestBody WeChatBindPhoneRequest request) {
        Account account = getAccount(accountId);

        // ?????????????????????
        if (account.getPhone() != null) {
            throw new HttpClientErrorException(HttpStatus.BAD_REQUEST, "??????????????????");
        }

        // ??????????????????????????? sessionKey
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof OAuth2Authentication) {
            Authentication userAuthentication = ((OAuth2Authentication) authentication).getUserAuthentication();
            OAuth2Request oAuth2Request = ((OAuth2Authentication) authentication).getOAuth2Request();
            // ??????????????????????????????
            if (oAuth2Request.getGrantType().equals(WeChatAppType.MINI_PROGRAM.getGrantType())) { // ???????????????
                String appId = oAuth2Request.getRequestParameters().get("app_id");
                try {
                    Object sessionKey = userAuthentication.getCredentials();
                    String sessionKeyStr = (String) sessionKey;
                    if (StringUtils.isBlank(sessionKeyStr)) {
                        throw new HttpClientErrorException(HttpStatus.FORBIDDEN, "????????????????????????");
                    }

                    PhoneInfo phoneInfo = weChatClient.decryptData(request.getEncryptedData(), sessionKeyStr, request.getIv(), PhoneInfo.class);

                    if(accountRepository.existsByPhone(phoneInfo.getPurePhoneNumber())) {
                        Account phoneAccount = accountRepository.findByPhone(phoneInfo.getPurePhoneNumber());
                        // ??????????????????????????????????????????
                        boolean phoneAccountHasBoundWeApp = phoneAccount.getWeChatAppBindings().stream()
                                .anyMatch(ab -> ab.getAppId().equals(appId));
                        if (!phoneAccountHasBoundWeApp) {
                            // ????????????????????????
                            WeChatAppBinding binding = account.getWeChatAppBindings().stream()
                                    .filter(ab -> ab.getAppId().equals(appId))
                                    .findFirst()
                                    .orElseThrow(() -> new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "??????????????????"));
                            binding.setAccount(phoneAccount);
                            phoneAccount.getWeChatAppBindings().add(binding);

                            // ??????????????????
                            try {
                                accountMergeManager.switchTo(phoneAccount);
                            } catch (Exception e) {
                                log.warn("??????????????????[{} -> {}]", account.getId(), phoneAccount.getId(), e);
                                throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "????????????????????????");
                            }

                            // ??????????????????
                            account.setDeleted(true);
                        } else {
                            throw new HttpClientErrorException(HttpStatus.BAD_REQUEST, "????????????????????????????????????");
                        }
                    } else {
                        // ????????????
                        account.setPhone(phoneInfo.getPurePhoneNumber());
                    }
                    return;
                } catch (HttpClientErrorException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("????????????????????????, sessionKey:{} encryptedData:{} iv:{}", userAuthentication.getCredentials(), request.getEncryptedData(), request.getIv());
                    throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "?????????????????????????????????");
                }
            }
        }

        throw new HttpClientErrorException(HttpStatus.BAD_REQUEST, "??????????????????????????????");
    }

    @Override
    @PreAuthorize("(#oauth2.client and #oauth2.clientHasRole('ROLE_SERVICE_CLIENT')) or (#oauth2.user and #accountId.equals(authentication.principal.id)) ")
    public List<WeChatBindingResponse> getByAppIds(@PathVariable UUID accountId, @PathVariable List<String> appIds) {
        Account account = getAccount(accountId);

        List<WeChatAppBinding> bindings = weChatAppBindingRepository.findByAccountAndAppIdIn(account, appIds);
        if (bindings != null && !bindings.isEmpty()) {
            return bindings.stream().map(binding -> {
                WeChatBindingResponse resp = new WeChatBindingResponse();
                BeanUtils.copyProperties(binding, resp);
                return resp;
            }).collect(Collectors.toList());
        }

        return null;
    }

    private Account getAccount(UUID id) {
        return accountRepository.findById(id).orElseThrow(() -> new HttpClientErrorException(HttpStatus.NOT_FOUND, "???????????????"));
    }

    private WeChatApp getWeChatApp(String appId) {
        return weChatGrantProperties.getApps()
                .stream()
                .filter(app -> app.getAppId().equals(appId))
                .findFirst()
                .orElseThrow(() -> new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "???????????????app[" + appId + "]"));
    }
}

package com.coderintuition.CoderIntuition.controllers;

import com.coderintuition.CoderIntuition.common.CodeTemplateFiller;
import com.coderintuition.CoderIntuition.common.Constants;
import com.coderintuition.CoderIntuition.common.Utils;
import com.coderintuition.CoderIntuition.config.AppProperties;
import com.coderintuition.CoderIntuition.enums.ActivityType;
import com.coderintuition.CoderIntuition.enums.SubmissionStatus;
import com.coderintuition.CoderIntuition.enums.TestStatus;
import com.coderintuition.CoderIntuition.models.*;
import com.coderintuition.CoderIntuition.pojos.request.ActivityRequestDto;
import com.coderintuition.CoderIntuition.pojos.request.JZSubmissionRequestDto;
import com.coderintuition.CoderIntuition.pojos.request.ProduceOutputDto;
import com.coderintuition.CoderIntuition.pojos.request.RunRequestDto;
import com.coderintuition.CoderIntuition.pojos.response.JzSubmissionCheckResponse;
import com.coderintuition.CoderIntuition.pojos.response.SubmissionResponse;
import com.coderintuition.CoderIntuition.pojos.response.TestRunResponse;
import com.coderintuition.CoderIntuition.pojos.response.TokenResponse;
import com.coderintuition.CoderIntuition.repositories.*;
import com.coderintuition.CoderIntuition.security.CurrentUser;
import com.coderintuition.CoderIntuition.security.UserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

@Slf4j
@RestController
public class SubmissionController {

    @Autowired
    ProblemRepository problemRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SubmissionRepository submissionRepository;

    @Autowired
    TestResultRepository testResultRepository;

    @Autowired
    SimpMessagingTemplate simpMessagingTemplate;

    @Autowired
    ActivityController activityController;

    @Autowired
    AppProperties appProperties;

    @PutMapping("/submission/judge0callback")
    public void submissionCallback(@RequestBody JzSubmissionCheckResponse data) throws IOException {
        log.info("PUT /submission/judge0callback, data={}", data.toString());

        // get submission info
        JzSubmissionCheckResponse result = Utils.retrieveFromJudgeZero(data.getToken(), appProperties);
        log.info("result={}", result.toString());

        // wait until test run is written to db from createSubmission
        await().atMost(15, SECONDS).until(() -> submissionRepository.findByToken(result.getToken()).isPresent());
        log.info("Done waiting for submission to be written to db");

        // fetch the submission from the db
        Submission submission = submissionRepository.findByToken(result.getToken()).orElseThrow();

        List<TestResult> testResults = new ArrayList<>();

        // save the results of the submission
        if (result.getStatus().getId() >= 6) { // error
            submission.setStatus(SubmissionStatus.ERROR);
            String stderr = "";
            if (result.getCompileOutput() != null) {
                stderr = result.getCompileOutput();
            } else if (result.getStderr() != null) {
                stderr = result.getStderr();
            }
            submission.setStderr(Utils.formatErrorMessage(submission.getLanguage(), stderr));

        } else if (result.getStatus().getId() == 3) { // no errors
            // everything above the line is stdout, everything below is test results
            String[] split = result.getStdout().trim().split(Constants.IO_SEPARATOR);
            submission.setOutput(split[1]);
            // set status as passed at first and overwrite if any test failed
            submission.setStatus(SubmissionStatus.ACCEPTED);

            for (String str : split[1].split("\n")) {
                // test results are formatted: {test num}|{status}|{expected output}|{run output}
                // runtime error results are formatted: {test num}|{status}|{error message}
                String[] testResult = str.split("\\|");
                log.info("testResult={}", Arrays.toString(testResult));

                if (testResult.length == 4) { // no errors
                    String num = testResult[0];
                    String status = testResult[1];
                    // create the test result object to be saved into the db
                    TestResult testResultObj = new TestResult();
                    testResultObj.setSubmission(submission);
                    testResultObj.setStatus(TestStatus.valueOf(status.toUpperCase()));
                    // retrieve the test case for this test result
                    TestCase testCase = submission.getProblem().getOrderedTestCases().get(Integer.parseInt(num));
                    testResultObj.setInput(testCase.getInput());
                    testResultObj.setExpectedOutput(testResult[2]);

                    // test case failed
                    if (status.equals(TestStatus.FAILED.toString())) {
                        testResultObj.setOutput(testResult[3]);
                        // set overall submission status to failed if the status is not already ERROR
                        if (submission.getStatus() != SubmissionStatus.ERROR) {
                            submission.setStatus(SubmissionStatus.REJECTED);
                        }
                    } else {
                        testResultObj.setOutput("");
                    }

                    // add the test result to the list of test results
                    testResults.add(testResultObj);

                } else if (testResult.length == 2) { // runtime errors
                    submission.setStatus(SubmissionStatus.ERROR);
                    submission.setStderr(Utils.formatErrorMessage(submission.getLanguage(), testResult[1]));
                }
            }
            submission.setTestResults(testResults);
        }

        // send message to frontend
        SubmissionResponse submissionResponse = SubmissionResponse.fromSubmission(submission);
        this.simpMessagingTemplate.convertAndSend(
            "/secured/" + submission.getUser().getId() + "/submission",
            submissionResponse
        );
        log.info(
            "Sent submission over websocket, destination=/secured/{}/submission, submissionResponse={}",
            submission.getUser().getId(),
            submissionResponse
        );

        // save the submission into the db
        submissionRepository.save(submission);
    }

    @MessageMapping("/secured/{userId}/submission")
    public void createSubmission(@DestinationVariable Long userId, Message<RunRequestDto> message) throws Exception {
        RunRequestDto submissionRequestDto = message.getPayload();
        log.info("Received websocket message, destination=/secured/{}/submission, submissionRequestionDto={}", userId, submissionRequestDto.toString());

        // retrieve the problem
        Problem problem = problemRepository.findById(submissionRequestDto.getProblemId()).orElseThrow();

        // wrap the code into the submission template
        CodeTemplateFiller filler = CodeTemplateFiller.getInstance();
        String functionName = Utils.getFunctionName(submissionRequestDto.getLanguage(), problem.getCode(submissionRequestDto.getLanguage()));
        String primarySolution = problem.getSolutions().stream().filter(Solution::getIsPrimary).findFirst().orElseThrow().getCode(submissionRequestDto.getLanguage());
        // fill in the submission template with the arguments/return type for this test run
        String code = filler.getSubmissionCode(submissionRequestDto.getLanguage(), submissionRequestDto.getCode(), primarySolution,
            functionName, problem.getOrderedArguments(), problem.getReturnType());
        log.info("Generated submission code from template, code={}", code);

        // setup stdin
        StringBuilder stdin = new StringBuilder();
        for (TestCase testCase : problem.getTestCases()) {
            stdin.append(testCase.getInput()).append("\n");
            stdin.append(Constants.IO_SEPARATOR);
        }

        // create request to JudgeZero
        JZSubmissionRequestDto jzSubmissionRequestDto = new JZSubmissionRequestDto();
        jzSubmissionRequestDto.setSourceCode(code);
        jzSubmissionRequestDto.setLanguageId(Utils.getLanguageId(submissionRequestDto.getLanguage()));
        jzSubmissionRequestDto.setStdin(stdin.toString());
        jzSubmissionRequestDto.setCallbackUrl(appProperties.getJudge0().getCallbackUrl() + "/submission/judge0callback");

        // send request to JudgeZero
        String token = Utils.submitToJudgeZero(jzSubmissionRequestDto, appProperties);
        log.info("Submitted submission to JudgeZero, token={}, jzSubmissionRequestDto={}", token, jzSubmissionRequestDto.toString());

        // save submission to db
        Submission submission = new Submission();
        User user = userRepository.findById(userId).orElseThrow();
        submission.setUser(user);
        submission.setCode(submissionRequestDto.getCode());
        submission.setLanguage(submissionRequestDto.getLanguage());
        submission.setStatus(SubmissionStatus.RUNNING);
        submission.setProblem(problem);
        submission.setToken(token);
        submissionRepository.save(submission);
        log.info("Saved submission to database, submission={}", submission);

        // create Activity
        ActivityRequestDto activityRequestDto = new ActivityRequestDto(
            ActivityType.SUBMIT_PROBLEM,
            problem.getId(),
            null,
            submission.getId(),
            null);
        activityController.createActivity(activityRequestDto, user);
    }
}

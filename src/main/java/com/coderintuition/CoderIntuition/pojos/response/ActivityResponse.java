package com.coderintuition.CoderIntuition.pojos.response;

import com.coderintuition.CoderIntuition.enums.ActivityType;
import com.coderintuition.CoderIntuition.enums.SubmissionStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ActivityResponse {
    private ActivityType activityType;
    private String problemName;
    private String problemUrl;
    private String readingName;
    private String readingUrl;
    private SubmissionStatus submissionStatus;
    private Date createdDate;
}

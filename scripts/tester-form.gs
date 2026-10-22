/**
 * Creates the Taal beta tester sign-up form in your Google account (Google Apps Script).
 * Use: script.google.com → New project → paste this file → Run "createTesterForm" → allow access.
 * The form's links appear under View → Execution log. Responses go to the form's Responses tab
 * (you can link a Google Sheet from there).
 */
function createTesterForm() {
  const form = FormApp.create('Taal beta: tester sign-up');
  form.setDescription(
    'Taal is a free Android app for heart-rate zone running: your phone stays in your pocket with the screen off ' +
    'and vibrates when you drift out of your zone.\n\n' +
    "I'm looking for 5–9 runners in India to try it on 3+ runs over the next 3 weeks and tell me what breaks. " +
    'Takes 2 minutes. I only use your details to send you the app and updates.'
  );
  form.setProgressBar(false);
  form.setConfirmationMessage(
    "Thanks! I'll message you within a few days if you're picked (I'm choosing a mix of phone brands and sensors)."
  );

  form.addTextItem().setTitle('Your name').setRequired(true);
  form.addTextItem()
    .setTitle('Email')
    .setHelpText('To send you the download link and updates.')
    .setValidation(FormApp.createTextValidation().requireTextIsEmail().build())
    .setRequired(true);
  form.addTextItem().setTitle('City').setRequired(true);

  form.addTextItem()
    .setTitle('Phone brand and model')
    .setHelpText('e.g. Redmi Note 13, Samsung Galaxy A35, OnePlus Nord CE 4. Settings → About phone shows it.')
    .setRequired(true);
  form.addMultipleChoiceItem()
    .setTitle('Android version')
    .setHelpText('Settings → About phone → Software information. Taal needs Android 12 or newer.')
    .setChoiceValues(['16 or newer', '15', '14', '13', '12', "Older than 12", "Not sure"])
    .setRequired(true);

  form.addMultipleChoiceItem()
    .setTitle('Heart-rate sensor you would use')
    .setChoiceValues([
      'Chest strap',
      'Armband',
      'Watch that can broadcast heart rate (Garmin, Coros, Amazfit…)',
      'Samsung Galaxy Fit3',
      "None yet",
    ])
    .showOtherOption(true)
    .setRequired(true);
  form.addTextItem()
    .setTitle('Sensor brand and model')
    .setHelpText('e.g. Polar H10, Garmin HRM-Dual, Coospo H808S, Amazfit Bip 5. Skip if none.');

  form.addMultipleChoiceItem()
    .setTitle('How often do you run?')
    .setChoiceValues(['1–2 times a week', '3–4 times a week', '5+ times a week', 'Just starting'])
    .setRequired(true);
  form.addMultipleChoiceItem()
    .setTitle('Usual run distance')
    .setChoiceValues(['Under 5 km', '5–10 km', '10–21 km', 'More than 21 km'])
    .setRequired(true);

  form.addCheckboxItem()
    .setTitle('Which apps do you use now?')
    .setChoiceValues(['Strava', 'Samsung Health', 'Google Fit / Health Connect', 'Garmin Connect', 'None'])
    .showOtherOption(true);

  form.addMultipleChoiceItem()
    .setTitle('Can you do 3+ runs with Taal in the next 3 weeks and send quick feedback after them?')
    .setHelpText('Feedback is one button in the app; it attaches the log for you.')
    .setChoiceValues(['Yes', 'No'])
    .setRequired(true);
  form.addMultipleChoiceItem()
    .setTitle('OK to join a small WhatsApp group for the beta?')
    .setChoiceValues(['Yes', 'No, email is better'])
    .setRequired(true);
  form.addTextItem()
    .setTitle('WhatsApp number (optional)')
    .setHelpText('Only if you said yes above.');

  form.addParagraphTextItem().setTitle('Anything else? (optional)');

  form.setAcceptingResponses(true);
  // Forms made from scripts may start unpublished; publish when this Apps Script version supports it.
  if (typeof form.setPublished === 'function') form.setPublished(true);

  const url = form.getPublishedUrl();
  Logger.log('Share this link:  ' + form.shortenFormUrl(url));
  Logger.log('Edit the form:    ' + form.getEditUrl());
}

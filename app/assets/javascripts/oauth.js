requirejs.config({
    baseUrl: '/api/assets/javascripts/',
    paths: {
        'jquery': 'libs/jquery.min',
        'rlite': 'libs/rlite.min',
        'jquery-ext': 'utils/jquery.ext',
        'bootstrap': 'libs/bootstrap.min',
        'bootstrap-switch': 'libs/bootstrap-switch.min',
        'knockout': 'libs/knockout',
        'knockout-bindings': 'utils/knockout-bindings',
        'ko_validation' : 'libs/knockout.validation',
        'moment' : 'libs/moment',
        'bootstrap-datetimepicker': 'libs/bootstrap-datetimepicker',
        'bootpag' : 'libs/bootpag',
        'toastr' : 'libs/toastr.min',
        'fileUpload' : 'libs/fileUpload',
        'jsoneditor' : 'libs/jsoneditor',
        'ajv': 'libs/ajv.min'
    },
    shim : {
        "ko.validation": {
            deps: ['knockout']
        }
    }
});



require(['require', 'knockout', 'jquery', 'bootstrap'], function(require, router, ko, message) {

    $(function() {
        $(document).on('submit','form.oauth2Form',function(){

            var form = $(this);
            var params = (new URL(window.location)).searchParams;

            $.post("/oauth/authorize", form.serializeJson()).done(function (resp, status, xhr) {
                var redirect_uri = params.get("redirect_uri")+"?state="+params.get("state")+"&token_type=Bearer"+"&access_token="+resp.accessToken+"&expires_in="+resp.expiresIn;
                if (window.baseUrl) redirect_uri=baseUrl+redirect_uri;
                window.location.replace(redirect_uri);
            });
        });
    });

    function initMainPage() {
        $.get({url: "/authorization"+window.location.search, dataType: "html"}).done(function(response, status, xhr ) {
            $("body").html(response);

        }).fail(function (xhr, status, err) {
            if (localStorage.getItem("token")) localStorage.removeItem("token");
            else initLoginPage();
        });
    }

    function initLoginPage() {
        $(function() {
            $("body").html($('#login-tpl').html());
            //Model for errors
            function LoginModel(){
                var self = this;
                self.errorInfo = ko.observable('');
                self.hideMessages = function() {
                    self.errorInfo('');
                };

                self.hideMessages();
            }
            var loginModel = new LoginModel();
            ko.applyBindings(loginModel, $('#error-block')[0]);
            $(".form-login").on("submit", function (e) {
                e.preventDefault();
                var form = $(this);
                form.find('.error-box').addClass('hide');
                loginModel.hideMessages();
                $.post("/login", form.serializeJson()).done(function (resp, status, xhr) {
                    localStorage.setItem("token", resp.token);
                    initMainPage();
                }).fail(function (res) {
                    loginModel.errorInfo(message.getMessage(res));
                    localStorage.removeItem("token");
                    form.find('.error-box').removeClass('hide');
                });
            });

        });
    }

    if (localStorage.getItem("token")) {
        initMainPage();
    } else {
        initLoginPage();
    }
});